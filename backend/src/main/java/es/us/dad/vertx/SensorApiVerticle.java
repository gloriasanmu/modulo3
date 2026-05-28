package es.us.dad.vertx;

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
}