/**
 * ESP32 SENSOR — Módulo 2.3: Filtro de Desescarche
 *
 * Función: Lee la temperatura del DHT11 y la publica cada 10 s
 * mediante MQTT al topic park/P1/food/freezer/S1/temp.
 *
 * Hardware:
 *   DHT11 VCC  → 3.3V
 *   DHT11 GND  → GND
 *   DHT11 DATA → GPIO 4
 */

#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include "DHT.h"

// aquí le metemos los datos del hotspot
const char* WIFI_SSID     = "junimo";
const char* WIFI_PASSWORD = "holaholakk";
const char* MQTT_BROKER   = "10.238.31.189";   // IP del PC con Mosquitto
const int   MQTT_PORT     = 1883;

// Identificadores de parque / sensor (deben coincidir con Vert.x)
const char* PARK_ID    = "P1";
const char* SENSOR_ID  = "S1";
// Topic resultante: park/P1/food/freezer/S1/temp
char MQTT_TOPIC[64];

// Intervalo de publicación no bloqueante (10 000 ms)
const unsigned long PUBLISH_INTERVAL = 10000;
unsigned long lastPublishTime = 0;

// ── Hardware ──────────────────────────────────────────────
#define DHTPIN  4
#define DHTTYPE DHT11
DHT dht(DHTPIN, DHTTYPE);

// ── Clientes de red ───────────────────────────────────────
WiFiClient   espClient;
PubSubClient mqttClient(espClient);

// ── Prototipos ────────────────────────────────────────────
void connectWiFi();
void connectMQTT();
void publishTemperature(float temp);

// ─────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    dht.begin();

    // Construir el topic dinámicamente
    snprintf(MQTT_TOPIC, sizeof(MQTT_TOPIC),
             "park/%s/food/freezer/%s/temp", PARK_ID, SENSOR_ID);

    Serial.printf("\n🌡️  ESP32 Sensor iniciando — topic: %s\n", MQTT_TOPIC);

    connectWiFi();

    mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
    // Este ESP32 solo publica; no necesita callback de suscripción
}

// ─────────────────────────────────────────────────────────
void loop() {
    // Mantener conexiones activas
    if (WiFi.status() != WL_CONNECTED) connectWiFi();
    if (!mqttClient.connected())        connectMQTT();
    mqttClient.loop();  // Mantiene vivo el keep-alive MQTT

    // Temporización no bloqueante
    unsigned long currentMillis = millis();
    if (currentMillis - lastPublishTime >= PUBLISH_INTERVAL) {
        lastPublishTime = currentMillis;

        float temp = dht.readTemperature();
        float hum  = dht.readHumidity();

        if (isnan(temp) || isnan(hum)) {
            Serial.println("❌ Error leyendo DHT11. Abortando ciclo.");
            return;
        }

        Serial.printf("📖 Lectura → Temp: %.2f°C  Hum: %.1f%%\n", temp, hum);
        publishTemperature(temp);
    }
}

// ─────────────────────────────────────────────────────────
void publishTemperature(float temp) {
    // Construir payload JSON
    StaticJsonDocument<200> doc;
    doc["sensorId"]  = SENSOR_ID;
    doc["parkId"]    = PARK_ID;
    doc["value"]     = temp;          // °C
    doc["timestamp"] = millis();

    String payload;
    serializeJson(doc, payload);

    Serial.printf("📤 Publicando en [%s]: %s\n", MQTT_TOPIC, payload.c_str());

    if (mqttClient.publish(MQTT_TOPIC, payload.c_str())) {
        Serial.println("   ✅ Publicación OK");
    } else {
        Serial.println("   ❌ Publicación fallida");
    }
}

// ─────────────────────────────────────────────────────────
void connectWiFi() {
    Serial.print("📶 Conectando a WiFi...");
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.printf("\n✅ WiFi conectado. IP: %s\n", WiFi.localIP().toString().c_str());
}

void connectMQTT() {
    while (!mqttClient.connected()) {
        Serial.print("🔌 Conectando a Broker MQTT...");
        // Client ID único para este dispositivo
        String clientId = String("ESP32_Sensor_") + SENSOR_ID;
        if (mqttClient.connect(clientId.c_str())) {
            Serial.println(" ✅ Conectado");
        } else {
            Serial.printf(" ❌ Fallo rc=%d — reintentando en 5s\n", mqttClient.state());
            delay(5000);
        }
    }
}