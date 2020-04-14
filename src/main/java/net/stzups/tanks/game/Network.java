package net.stzups.tanks.game;

import net.stzups.tanks.Tanks;
import net.stzups.tanks.server.Connection;
import net.stzups.tanks.server.PacketListener;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

class Network implements PacketListener {

    private static final Logger logger = java.util.logging.Logger.getLogger(Tanks.class.getName());

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Game game;

    Network(Game game) {
        this.game = game;
        Tanks.server.addPacketListener(this);
    }

    void tick() {
        executorService.submit(() -> {
            JSONObject[][] sectors = new JSONObject[World.WORLD_SECTORS][World.WORLD_SECTORS];

            for (Map.Entry<Connection, Player> entry : game.connectionPlayerMap.entrySet()) {
                Player player = entry.getValue();
                JSONObject payload = new JSONObject();
                for (int x = Math.max(0, (int) (player.x - player.viewportWidth / 2.0 / World.SECTOR_SIZE)); x < Math.ceil(player.x + player.viewportWidth / 2.0 / World.SECTOR_SIZE); x++) {
                    for (int y = Math.max(0, (int) (player.y - player.viewportHeight / 2.0 / World.SECTOR_SIZE)); y < Math.ceil(player.y + player.viewportHeight / 2.0 / World.SECTOR_SIZE); y++) {
                        Sector sector = game.world.sectors[x][y];
                        if (sector != null) {
                            if (sectors[x][y] == null) {
                                JSONObject jsonSector = sector.serialize();
                                sectors[x][y] = jsonSector;
                                payload.put(x + "," + y, jsonSector);
                            } else {
                                payload.put(x + "," + y, sectors[x][y]);
                            }
                        }
                    }
                }
                entry.getKey().sendText(payload.toString());
            }
        });
    }

    void stop() {
        executorService.shutdown();
    }

    public void onTextPacket(Connection connection, String rawPayload) {
        if (!game.connectionPlayerMap.containsKey(connection)) {
            try {
                JSONArray payload = new JSONArray(rawPayload);
                if (payload.getString(0).equalsIgnoreCase("newPlayer")) {
                    JSONArray newClient = payload.getJSONArray(1);
                    String name = newClient.getString(0);
                    int viewportWidth = newClient.getInt(1);
                    int viewportHeight = newClient.getInt(2);
                    Player player = new Player(UUID.randomUUID(), 0, 0, 0, 0, 0, 10, 20, name, viewportWidth, viewportHeight, new Player.Turret(0, 4, 10), new ArrayList<>());
                    game.connectionPlayerMap.put(connection, player);
                    game.world.addObject(player);
                    logger.info("New player " + player.getName());

                    connection.sendText("[newPlayer,[" + player.id + "]");
                } else {
                    logger.warning("Kicking " + connection.getSocket().getInetAddress().getHostAddress() + " after sending " + rawPayload + ", should have gotten newPlayer, instead got " + payload.getString(0));
                }
            } catch (JSONException e) {
                logger.warning("Kicking " + connection.getSocket().getInetAddress().getHostAddress() + " after sending " + rawPayload + ", parsing newPlayer packet caused " + e.getMessage());
                connection.close(true);
            }
        } else {
            Player player = game.connectionPlayerMap.get(connection);
            logger.info(player.getName() + ": " + rawPayload);
        }
    }

    public void removeConnection(Connection connection) {
        if (game.connectionPlayerMap.containsKey(connection)) {
            game.world.removeObject(game.connectionPlayerMap.get(connection));
            game.connectionPlayerMap.remove(connection);
        }
    }

    public void addConnection(Connection connection) {

    }
}
