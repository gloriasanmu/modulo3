/*package es.us.dad.vertx;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.mqtt.MqttClient;
import io.netty.handler.codec.mqtt.MqttQoS;

import java.util.HashMap;
import java.util.Map;

public class SensorApiVerticle extends AbstractVerticle {

    private MqttClient mqttClient;
    // Estructura de estado en memoria volátil (K-V)
    private final Map<String, JsonObject> telemetryData = new HashMap<>();

    @Override
    public void start(Promise<Void> startPromise) {
        setupMqttClient()
                .compose(v -> setupHttpServer())
                .onSuccess(server -> {
                    System.out.println("✅ Servidor Híbrido: HTTP(8080) & MQTT desplegados.");
                    startPromise.complete();
                })
                .onFailure(err -> {
                    System.err.println("❌ Fallo estructural de inicio: " + err.getMessage());
                    startPromise.fail(err);
                });
    }

    private io.vertx.core.Future<Void> setupMqttClient() {
        Promise<Void> promise = Promise.promise();
        mqttClient = MqttClient.create(vertx);

        mqttClient.connect(1883, "localhost").onSuccess(conn -> {
            promise.complete();
        }).onFailure(promise::fail);

        return promise.future();
    }

    private io.vertx.core.Future<io.vertx.core.http.HttpServer> setupHttpServer() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.get("/api/telemetry").handler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(JsonObject.mapFrom(telemetryData).encodePrettily());
        });

        router.post("/api/telemetry").handler(ctx -> {
            try {
                JsonObject payload = ctx.body().asJsonObject();
                String deviceId = payload.getString("device_id");

                telemetryData.put(deviceId, payload);
                System.out.println("📥 REST POST interceptado [" + deviceId + "]: Value = " + payload.getDouble("value"));

                // Finalizar la petición HTTP para liberar la conexión del ESP32
                ctx.response().setStatusCode(201).end(new JsonObject().put("status", "accepted").encode());

                // Disparo asíncrono diferido de actuación electromecánica
                System.out.println("⏳ (Retardo 15s) Armando instrucción MQTT para actuador...");

                vertx.setTimer(15000, timerId -> {
                    if (mqttClient.isConnected()) {
                        String targetTopic = "devices/relay1/command";
                        JsonObject commandON = new JsonObject().put("state", "ON");

                        mqttClient.publish(targetTopic, Buffer.buffer(commandON.encode()), MqttQoS.AT_LEAST_ONCE, false, false);
                        System.out.println("📤 Señal de actuación ON despachada.");

                        // Retorno asíncrono a estado de reposo tras 5 segundos
                        vertx.setTimer(5000, offTimerId -> {
                            mqttClient.publish(targetTopic, Buffer.buffer(new JsonObject().put("state", "OFF").encode()), MqttQoS.AT_LEAST_ONCE, false, false);
                            System.out.println("📤 Señal de actuación OFF despachada.");
                        });
                    }
                });
            } catch (Exception e) {
                ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Error de serialización entrante").encode());
            }
        });

        // Endpoint GET con filtro mediante Query Parameters: por ejemplo /api/telemetry/filter?minValue=25.0
        router.get("/api/telemetry/filter").handler(ctx -> {
            // 1. Extracción y validación del parámetro de consulta
            java.util.List<String> minValueParams = ctx.queryParam("minValue");

            if (minValueParams.isEmpty()) {
                ctx.response()
                        .setStatusCode(400)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("error", "Falta el parámetro obligatorio 'minValue'").encode());
                return;
            }

            try {
                // 2. Conversión estricta de tipo
                double minValue = Double.parseDouble(minValueParams.get(0));
                io.vertx.core.json.JsonArray filteredResults = new io.vertx.core.json.JsonArray();

                // 3. Filtrado iterativo sobre la estructura de datos en memoria
                for (JsonObject telemetry : telemetryData.values()) {
                    if (telemetry.containsKey("value") && telemetry.getDouble("value") >= minValue) {
                        filteredResults.add(telemetry);
                    }
                }

                // 4. Retorno del subconjunto de datos
                ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(filteredResults.encodePrettily());

            } catch (NumberFormatException e) {
                // Intercepción de fallos de parseo si el cliente envía cadenas alfanuméricas
                ctx.response()
                        .setStatusCode(400)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("error", "Formato numérico inválido para 'minValue'").encode());
            }
        });

        return vertx.createHttpServer().requestHandler(router).listen(8080);
    }
}*/

package es.us.dad.vertx;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.mqtt.MqttClient;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

import java.util.HashMap;
import java.util.Map;

/**
 * SensorApiVerticle — Módulo 2.3: Filtro de Desescarche
 *
 * Responsabilidades:
 *  1. Suscribirse al topic MQTT de temperatura del congelador.
 *  2. Detectar si la temperatura supera el umbral crítico (-10°C)
 *     durante más de 20 minutos → clasificarlo como fallo mecánico.
 *  3. En caso de alarma: registrar en BD, publicar comando ON
 *     al compresor y OFF 30 s después.
 *  4. Exponer endpoints REST:
 *       GET    /api/v1/parks/:parkId/food/freezer/:sensorId/defrost_logs
 *       DELETE /api/v1/parks/:parkId/food/alarms
 */
public class SensorApiVerticle extends AbstractVerticle {

    private static final double DEFROST_THRESHOLD_CELSIUS = -10.0;
    private static final long   DEFROST_WINDOW_MS         = 1 * 10 * 1000L; // 20 minutos (para probar le pusimos 10 segundos)
    private static final long   COMPRESSOR_OFF_DELAY_MS   = 30_000L;

    // configurar la base de datos
    private static final String DB_HOST     = "127.0.0.1";
    private static final int    DB_PORT     = 3306;
    private static final String DB_NAME     = "iot_project";
    // le ponemos aquí nuestro usuario y contraseña de la conexión de mariaDB
    private static final String DB_USER     = "root";
    private static final String DB_PASSWORD = "iissi$root";

    private final Map<String, Long>    thresholdBreachStart = new HashMap<>();
    private final Map<String, Boolean> alarmActive          = new HashMap<>();

    // clientes de mqtt y de la base de datos
    private MqttClient mqttClient;
    private Pool dbClient;

    @Override
    public void start(Promise<Void> startPromise) {
        setupMqttClient()
                .compose(v -> setupDatabase())
                .compose(v -> setupHttpServer())
                .onSuccess(server -> {
                    System.out.println("✅ Servidor Híbrido desplegado: HTTP(8080) + MQTT + BD");
                    startPromise.complete();
                })
                .onFailure(err -> {
                    System.err.println("❌ Error de inicio: " + err.getMessage());
                    startPromise.fail(err);
                });
    }

    // le hacemso el setup a la base de datos
    private Future<Void> setupDatabase() {
        Promise<Void> promise = Promise.promise();

        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
                .setPort(DB_PORT)
                .setHost(DB_HOST)
                .setDatabase(DB_NAME)
                .setUser(DB_USER)
                .setPassword(DB_PASSWORD);

        PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

        try {
            dbClient = MySQLBuilder.pool()
                    .with(poolOptions)
                    .connectingTo(connectOptions)
                    .using(vertx)
                    .build();

            System.out.println("✅ Pool de conexiones MySQL creado.");
            promise.complete();
        } catch (Exception e) {
            System.err.println("❌ Fallo creando pool MySQL: " + e.getMessage());
            promise.fail(e);
        }

        return promise.future();
    }

    // ahora el setup al mqtt
    private Future<Void> setupMqttClient() {
        Promise<Void> promise = Promise.promise();
        mqttClient = MqttClient.create(vertx);

        mqttClient.publishHandler(message -> {
            String topic   = message.topicName();
            String payload = message.payload().toString(java.nio.charset.StandardCharsets.UTF_8);
            System.out.printf("📡 MQTT recibido [%s]: %s%n", topic, payload);
            handleTemperatureMessage(topic, payload);
        });

        mqttClient.connect(1883, "10.238.31.189")
                .onSuccess(conn -> {
                    mqttClient.subscribe("park/+/food/freezer/+/temp", MqttQoS.AT_LEAST_ONCE.value())
                            .onSuccess(ack -> System.out.println("📥 Suscrito a: park/+/food/freezer/+/temp"))
                            .onFailure(err -> System.err.println("❌ Error suscripción: " + err.getMessage()));
                    promise.complete();
                })
                .onFailure(promise::fail);

        return promise.future();
    }


    private void handleTemperatureMessage(String topic, String rawPayload) {
        JsonObject data;
        try {
            data = new JsonObject(rawPayload);
        } catch (Exception e) {
            System.err.println("❌ JSON inválido en topic " + topic + ": " + e.getMessage());
            return;
        }

        // Extraer parkId y sensorId del topic: park/P1/food/freezer/S1/temp
        String[] parts = topic.split("/");
        if (parts.length < 6) return;
        String parkId   = parts[1];
        String sensorId = parts[4];
        String stateKey = parkId + "/" + sensorId;

        double temp      = data.getDouble("value", 0.0);
        long   nowMillis = System.currentTimeMillis();

        System.out.printf("   🌡️  [%s] Temperatura: %.2f°C%n", stateKey, temp);

        if (temp > DEFROST_THRESHOLD_CELSIUS) {
            if (!thresholdBreachStart.containsKey(stateKey)) {
                // Primera vez que supera el umbral
                thresholdBreachStart.put(stateKey, nowMillis);
                System.out.printf("   ⏱️  [%s] Umbral superado — iniciando ventana de %d min%n",
                        stateKey, DEFROST_WINDOW_MS / 60000);
            } else {
                long    elapsed        = nowMillis - thresholdBreachStart.get(stateKey);
                boolean alreadyAlarmed = alarmActive.getOrDefault(stateKey, false);

                if (elapsed >= DEFROST_WINDOW_MS && !alreadyAlarmed) {
                    // ── FALLO MECÁNICO CONFIRMADO ────────────────────
                    System.out.printf("🚨 [%s] ALARMA DE DESESCARCHE — %.2f°C durante %d min%n",
                            stateKey, temp, elapsed / 60000);
                    alarmActive.put(stateKey, true);

                    String reason = "Temperatura > " + DEFROST_THRESHOLD_CELSIUS
                            + "°C durante más de " + (DEFROST_WINDOW_MS / 60000) + " minutos";

                    // Guardar en BD y activar compresor
                    saveDefrostLog(parkId, sensorId, reason, nowMillis, temp);

                } else if (!alreadyAlarmed) {
                    System.out.printf("   ⏳ [%s] Transcurridos %d min / %d min%n",
                            stateKey, elapsed / 60000, DEFROST_WINDOW_MS / 60000);
                }
            }
        } else {
            // Temperatura normalizada
            if (thresholdBreachStart.containsKey(stateKey)) {
                System.out.printf("   ✅ [%s] Temperatura normalizada (%.2f°C) — reseteando cronómetro%n",
                        stateKey, temp);
                thresholdBreachStart.remove(stateKey);
                alarmActive.remove(stateKey);
            }
        }
    }

    // para guardar en la base de datos y activar el compresor
    private void saveDefrostLog(String parkId, String sensorId,
                                String reason, long nowMillis, double temp) {
        // insertamos en defrost_logs
        dbClient.preparedQuery("INSERT INTO defrost_logs (parkId, sensorId) VALUES (?, ?)")
                .execute(Tuple.of(parkId, sensorId))
                .onSuccess(rows -> {
                    System.out.println("💾 Fallo guardado en defrost_logs.");

                    // insertamos en alarms
                    dbClient.preparedQuery(
                                    "INSERT INTO alarms (parkId, sensorId, reason) VALUES (?, ?, ?)")
                            .execute(Tuple.of(parkId, sensorId, reason))
                            .onSuccess(alarmRows -> {
                                System.out.println("🚨 Alarma registrada en BD.");
                                // activamos el compresor por mqtt
                                activateCompressor(parkId, sensorId);
                            })
                            .onFailure(err ->
                                    System.err.println("❌ Error insertando alarma: " + err.getMessage()));
                })
                .onFailure(err ->
                        System.err.println("❌ Error insertando defrost_log: " + err.getMessage()));
    }


    private void activateCompressor(String parkId, String actuatorId) {
        String topic = String.format("park/%s/food/compressor/%s/command", parkId, actuatorId);

        if (!mqttClient.isConnected()) {
            System.err.println("❌ MQTT desconectado — no se puede activar el compresor");
            return;
        }

        JsonObject cmdON = new JsonObject().put("command", "ON");
        mqttClient.publish(topic, Buffer.buffer(cmdON.encode()),
                MqttQoS.AT_LEAST_ONCE, false, false);
        System.out.printf("📤 [%s] Compresor ON despachado%n", topic);

        vertx.setTimer(COMPRESSOR_OFF_DELAY_MS, id -> {
            JsonObject cmdOFF = new JsonObject().put("command", "OFF");
            mqttClient.publish(topic, Buffer.buffer(cmdOFF.encode()),
                    MqttQoS.AT_LEAST_ONCE, false, false);
            System.out.printf("📤 [%s] Compresor OFF despachado (tras %d s)%n",
                    topic, COMPRESSOR_OFF_DELAY_MS / 1000);
        });
    }


    private Future<io.vertx.core.http.HttpServer> setupHttpServer() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // GET /api/v1/parks/:parkId/food/freezer/:sensorId/defrost_logs
        router.get("/api/v1/parks/:parkId/food/freezer/:sensorId/defrost_logs")
                .handler(ctx -> {
                    String parkId   = ctx.pathParam("parkId");
                    String sensorId = ctx.pathParam("sensorId");

                    dbClient.preparedQuery(
                                    "SELECT * FROM defrost_logs WHERE parkId = ? AND sensorId = ? ORDER BY startTime DESC")
                            .execute(Tuple.of(parkId, sensorId))
                            .onSuccess(rows -> {
                                JsonArray result = new JsonArray();
                                rows.forEach(row -> result.add(new JsonObject()
                                        .put("id",        row.getInteger("id"))
                                        .put("parkId",    row.getString("parkId"))
                                        .put("sensorId",  row.getString("sensorId"))
                                        .put("startTime", row.getValue("startTime").toString())
                                ));
                                System.out.printf("📋 GET defrost_logs [%s/%s] → %d registros%n",
                                        parkId, sensorId, result.size());
                                ctx.response().setStatusCode(200)
                                        .putHeader("content-type", "application/json")
                                        .end(result.encodePrettily());
                            })
                            .onFailure(err -> {
                                System.err.println("❌ Error consultando defrost_logs: " + err.getMessage());
                                ctx.response().setStatusCode(500)
                                        .putHeader("content-type", "application/json")
                                        .end(new JsonObject().put("error", "Error interno").encode());
                            });
                });

        // DELETE /api/v1/parks/:parkId/food/alarms
        router.delete("/api/v1/parks/:parkId/food/alarms")
                .handler(ctx -> {
                    String parkId = ctx.pathParam("parkId");

                    dbClient.preparedQuery("DELETE FROM alarms WHERE parkId = ?")
                            .execute(Tuple.of(parkId))
                            .onSuccess(rows -> {
                                // Resetear también el estado en memoria
                                alarmActive.entrySet().removeIf(e -> e.getKey().startsWith(parkId + "/"));
                                System.out.printf("🗑️  DELETE alarms [%s] → eliminadas %d alarmas%n",
                                        parkId, rows.rowCount());
                                ctx.response().setStatusCode(200)
                                        .putHeader("content-type", "application/json")
                                        .end(new JsonObject()
                                                .put("status", "ok")
                                                .put("deletedAlarms", rows.rowCount())
                                                .encode());
                            })
                            .onFailure(err -> {
                                System.err.println("❌ Error eliminando alarmas: " + err.getMessage());
                                ctx.response().setStatusCode(500)
                                        .putHeader("content-type", "application/json")
                                        .end(new JsonObject().put("error", "Error interno").encode());
                            });
                });

        // GET /api/telemetry (compatibilidad)
        router.get("/api/telemetry").handler(ctx ->
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                                .put("message", "Usa los endpoints REST del módulo 2.3").encode())
        );

        return vertx.createHttpServer().requestHandler(router).listen(8080);
    }
}