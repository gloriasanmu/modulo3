/*
Esta función lee la temperatura del DHT11 y la publica cada 10 s
mediante MQTT al topic park/P1/food/freezer/S1/temp.
*/

#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include "DHT.h"

// aquí tenemos que ponerle los datos del hotspot del teléfono
const char* WIFI_SSID     = "junimo";
const char* WIFI_PASSWORD = "holaholakk";
const char* MQTT_BROKER   = "10.238.31.189";   // IP del PC
const int   MQTT_PORT     = 1883;

// identificadores del parque y del sensor
const char* PARK_ID    = "P1";
const char* SENSOR_ID  = "S1";
// guardamos aquí topic resultante: park/P1/food/freezer/S1/temp
char MQTT_TOPIC[64];

// se publica cada 10 segundos
const unsigned long PUBLISH_INTERVAL = 10000;
unsigned long lastPublishTime = 0;

// definimos los pines del ESP32 
#define DHTPIN  4
#define DHTTYPE DHT11
DHT dht(DHTPIN, DHTTYPE);

WiFiClient   espClient;
PubSubClient mqttClient(espClient);

void connectWiFi();
void connectMQTT();
void publishTemperature(float temp);

void setup() {
    // inicializamos puerto sserie y sensor dht11
    Serial.begin(115200); 
    dht.begin();

    // para construir topic dinámicamente
    snprintf(MQTT_TOPIC, sizeof(MQTT_TOPIC),
             "park/%s/food/freezer/%s/temp", PARK_ID, SENSOR_ID);

    Serial.printf("\n🌡️  ESP32 Sensor iniciando — topic: %s\n", MQTT_TOPIC);

    connectWiFi();

    mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
    // Este ESP32 solo publica; no necesita callback de suscripción
}

void loop() {
    if (WiFi.status() != WL_CONNECTED) connectWiFi();
    if (!mqttClient.connected())        connectMQTT();
    mqttClient.loop();  // keep-alive MQTT

    // millis() sirve como funicón de temporizacion no bloqueante 
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


void publishTemperature(float temp) {
    // para construir el JSON
    StaticJsonDocument<200> doc;
    doc["sensorId"]  = SENSOR_ID;
    doc["parkId"]    = PARK_ID;
    doc["value"]     = temp; // en °C
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