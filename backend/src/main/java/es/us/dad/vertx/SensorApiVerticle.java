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

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.mqtt.MqttClient;
import io.netty.handler.codec.mqtt.MqttQoS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SensorApiVerticle — Módulo 2.3: Filtro de Desescarche
 *
 * Responsabilidades:
 *  1. Suscribirse al topic MQTT de temperatura del congelador.
 *  2. Detectar si la temperatura supera el umbral crítico (-10°C)
 *     durante más de 20 minutos → clasificarlo como fallo mecánico.
 *  3. En caso de alarma: registrar el evento, publicar comando ON
 *     al compresor y OFF 30 s después.
 *  4. Exponer endpoints REST:
 *       GET    /api/v1/parks/:parkId/food/freezer/:sensorId/defrost_logs
 *       DELETE /api/v1/parks/:parkId/food/alarms
 */
public class SensorApiVerticle extends AbstractVerticle {

    // ── Constantes de dominio ───────────────────────────────────────
    private static final double DEFROST_THRESHOLD_CELSIUS = -10.0;   // °C umbral crítico
    private static final long   DEFROST_WINDOW_MS         = 20 * 60 * 1000L; // 20 minutos
    private static final long   COMPRESSOR_OFF_DELAY_MS   = 30_000L; // 30 s después de ON

    // ── Estado en memoria ───────────────────────────────────────────
    /**
     * Para cada sensorId guarda el instante (ms) en que la temperatura
     * empezó a superar el umbral. null = sensor por debajo del umbral.
     */
    private final Map<String, Long>       thresholdBreachStart = new HashMap<>();
    private final Map<String, Boolean>    alarmActive          = new HashMap<>();

    /** Registro histórico de eventos de desescarche */
    private final List<JsonObject>        defrostLogs          = new ArrayList<>();

    /** Alarmas activas (se eliminan con DELETE) */
    private final List<JsonObject>        activeAlarms         = new ArrayList<>();

    private MqttClient mqttClient;

    // ── Ciclo de vida del Verticle ─────────────────────────────────
    @Override
    public void start(Promise<Void> startPromise) {
        setupMqttClient()
                .compose(v -> setupHttpServer())
                .onSuccess(server -> {
                    System.out.println("✅ Servidor Híbrido desplegado: HTTP(8080) + MQTT");
                    startPromise.complete();
                })
                .onFailure(err -> {
                    System.err.println("❌ Error de inicio: " + err.getMessage());
                    startPromise.fail(err);
                });
    }

    // ── MQTT ───────────────────────────────────────────────────────
    private io.vertx.core.Future<Void> setupMqttClient() {
        Promise<Void> promise = Promise.promise();
        mqttClient = MqttClient.create(vertx);

        mqttClient.publishHandler(message -> {
            String topic   = message.topicName();
            String payload = message.payload().toString(java.nio.charset.StandardCharsets.UTF_8);
            System.out.printf("📡 MQTT recibido [%s]: %s%n", topic, payload);
            handleTemperatureMessage(topic, payload);
        });

        mqttClient.connect(1883, "localhost")
                .onSuccess(conn -> {
                    // Suscripción genérica a TODOS los sensores de congelador de TODOS los parques
                    // Wildcard MQTT: park/+/food/freezer/+/temp
                    mqttClient.subscribe("park/+/food/freezer/+/temp", MqttQoS.AT_LEAST_ONCE.value())
                            .onSuccess(ack -> System.out.println("📥 Suscrito a: park/+/food/freezer/+/temp"))
                            .onFailure(err -> System.err.println("❌ Error suscripción: " + err.getMessage()));
                    promise.complete();
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Núcleo de la lógica de desescarche.
     * Invocado cada vez que llega un dato de temperatura por MQTT.
     *
     * Topic formato: park/{parkId}/food/freezer/{sensorId}/temp
     * Payload JSON:  {"sensorId":"S1","parkId":"P1","value":-5.0,"timestamp":12345}
     */
    private void handleTemperatureMessage(String topic, String rawPayload) {
        JsonObject data;
        try {
            data = new JsonObject(rawPayload);
        } catch (Exception e) {
            System.err.println("❌ JSON inválido en topic " + topic + ": " + e.getMessage());
            return;
        }

        // Extraer identificadores del topic  →  park/P1/food/freezer/S1/temp
        String[] parts = topic.split("/");
        if (parts.length < 6) return;
        String parkId   = parts[1];  // P1
        String sensorId = parts[4];  // S1
        String stateKey = parkId + "/" + sensorId;

        double temp      = data.getDouble("value", 0.0);
        long   nowMillis = System.currentTimeMillis();

        System.out.printf("   🌡️  [%s] Temperatura: %.2f°C%n", stateKey, temp);

        if (temp > DEFROST_THRESHOLD_CELSIUS) {
            // ── La temperatura supera el umbral (-10°C) ──────────────
            if (!thresholdBreachStart.containsKey(stateKey)) {
                // Primera vez que lo supera: arranca el cronómetro
                thresholdBreachStart.put(stateKey, nowMillis);
                System.out.printf("   ⏱️  [%s] Umbral superado — iniciando ventana de %d min%n",
                        stateKey, DEFROST_WINDOW_MS / 60000);
            } else {
                // Ya estaba en umbral: comprobar si llevamos más de 20 min
                long elapsed = nowMillis - thresholdBreachStart.get(stateKey);
                boolean alreadyAlarmed = alarmActive.getOrDefault(stateKey, false);

                if (elapsed >= DEFROST_WINDOW_MS && !alreadyAlarmed) {
                    // ── FALLO MECÁNICO CONFIRMADO ────────────────────
                    System.out.printf("🚨 [%s] ALARMA DE DESESCARCHE — %.2f°C durante %d min%n",
                            stateKey, temp, elapsed / 60000);
                    alarmActive.put(stateKey, true);

                    // Registrar en el log
                    JsonObject logEntry = new JsonObject()
                            .put("parkId",    parkId)
                            .put("sensorId",  sensorId)
                            .put("startTime", thresholdBreachStart.get(stateKey))
                            .put("alarmTime", nowMillis)
                            .put("elapsedMs", elapsed)
                            .put("tempAtAlarm", temp)
                            .put("reason",   "Temperatura > " + DEFROST_THRESHOLD_CELSIUS
                                    + "°C durante más de " + (DEFROST_WINDOW_MS / 60000) + " minutos");
                    defrostLogs.add(logEntry);

                    // Registrar alarma activa
                    JsonObject alarm = new JsonObject()
                            .put("parkId",   parkId)
                            .put("sensorId", sensorId)
                            .put("timestamp", nowMillis)
                            .put("type",     "DEFROST_FAILURE");
                    activeAlarms.add(alarm);

                    // Activar compresor
                    activateCompressor(parkId, sensorId, nowMillis);
                } else if (!alreadyAlarmed) {
                    System.out.printf("   ⏳ [%s] Transcurridos %d min / %d min%n",
                            stateKey, elapsed / 60000, DEFROST_WINDOW_MS / 60000);
                }
            }
        } else {
            // ── La temperatura volvió a bajar del umbral ─────────────
            if (thresholdBreachStart.containsKey(stateKey)) {
                System.out.printf("   ✅ [%s] Temperatura normalizada (%.2f°C) — reseteando cronómetro%n",
                        stateKey, temp);
                thresholdBreachStart.remove(stateKey);
                alarmActive.remove(stateKey);
            }
        }
    }

    /**
     * Publica el comando ON al compresor y, tras COMPRESSOR_OFF_DELAY_MS, el comando OFF.
     * Usa los timers no bloqueantes del EventLoop de Vert.x.
     */
    private void activateCompressor(String parkId, String actuatorId, long alarmTime) {
        // Topic: park/P1/food/compressor/A1/command
        String topic = String.format("park/%s/food/compressor/%s/command", parkId, actuatorId);

        if (!mqttClient.isConnected()) {
            System.err.println("❌ MQTT desconectado — no se puede activar el compresor");
            return;
        }

        // ON inmediato
        JsonObject cmdON = new JsonObject().put("command", "ON");
        mqttClient.publish(topic, Buffer.buffer(cmdON.encode()),
                MqttQoS.AT_LEAST_ONCE, false, false);
        System.out.printf("📤 [%s] Compresor ON despachado%n", topic);

        // OFF tras COMPRESSOR_OFF_DELAY_MS
        vertx.setTimer(COMPRESSOR_OFF_DELAY_MS, id -> {
            JsonObject cmdOFF = new JsonObject().put("command", "OFF");
            mqttClient.publish(topic, Buffer.buffer(cmdOFF.encode()),
                    MqttQoS.AT_LEAST_ONCE, false, false);
            System.out.printf("📤 [%s] Compresor OFF despachado (tras %d s)%n",
                    topic, COMPRESSOR_OFF_DELAY_MS / 1000);
        });
    }

    // ── HTTP / REST ────────────────────────────────────────────────
    private io.vertx.core.Future<io.vertx.core.http.HttpServer> setupHttpServer() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // ── GET /api/v1/parks/:parkId/food/freezer/:sensorId/defrost_logs ──
        router.get("/api/v1/parks/:parkId/food/freezer/:sensorId/defrost_logs")
                .handler(ctx -> {
                    String parkId   = ctx.pathParam("parkId");
                    String sensorId = ctx.pathParam("sensorId");

                    JsonArray result = new JsonArray();
                    for (JsonObject log : defrostLogs) {
                        if (parkId.equals(log.getString("parkId"))
                                && sensorId.equals(log.getString("sensorId"))) {
                            result.add(log);
                        }
                    }

                    System.out.printf("📋 GET defrost_logs [%s/%s] → %d registros%n",
                            parkId, sensorId, result.size());

                    ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(result.encodePrettily());
                });

        // ── DELETE /api/v1/parks/:parkId/food/alarms ───────────────
        router.delete("/api/v1/parks/:parkId/food/alarms")
                .handler(ctx -> {
                    String parkId = ctx.pathParam("parkId");

                    int before = activeAlarms.size();
                    activeAlarms.removeIf(a -> parkId.equals(a.getString("parkId")));
                    // Resetear el flag de alarma activa para ese parque
                    alarmActive.entrySet().removeIf(e -> e.getKey().startsWith(parkId + "/"));
                    int removed = before - activeAlarms.size();

                    System.out.printf("🗑️  DELETE alarms [%s] → eliminadas %d alarmas%n",
                            parkId, removed);

                    ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject()
                                    .put("status", "ok")
                                    .put("deletedAlarms", removed)
                                    .encode());
                });

        // ── GET /api/telemetry (legado, compatibilidad) ────────────
        router.get("/api/telemetry").handler(ctx ->
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("message", "Usa los endpoints REST del módulo 2.3").encode())
        );

        return vertx.createHttpServer().requestHandler(router).listen(8080);
    }
}