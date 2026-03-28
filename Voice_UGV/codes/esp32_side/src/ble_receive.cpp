#include "ble_receive.h"
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <string.h>
#include <esp_system.h> // Required for MAC address spoofing

// UUIDs must match VoiceCommandManager.java
#define SERVICE_UUID     "12345678-1234-1234-1234-123456789abc"
#define CTRL_CHAR_UUID   "12345678-1234-1234-1234-123456789002"
#define RESULT_CHAR_UUID "12345678-1234-1234-1234-123456789003"

static BLEServer* pServer     = nullptr;
static BLECharacteristic* pCtrlChar   = nullptr;
static BLECharacteristic* pResultChar = nullptr;

static bool deviceConnected = false;
static bool commandReady    = false;
static char commandBuf[16]  = {0};

// Target BLE MAC: EC:94:CB:4A:6E:9E
// Base MAC = Target MAC - 2 (9E - 2 = 9C)
uint8_t custom_base_mac[] = {0xEC, 0x94, 0xCB, 0x4A, 0x6E, 0x9C};

// ── BLE callbacks ─────────────────────────────────────────────────────────────

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* s) override {
        deviceConnected = true;
        Serial.println("BLE client connected");
    }
    void onDisconnect(BLEServer* s) override {
        deviceConnected = false;
        commandReady    = false;
        Serial.println("BLE disconnected — advertising...");
        s->startAdvertising();
    }
};

class CtrlCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        std::string val = c->getValue();
        if (val.empty()) return;

        // Accept only known command strings — ignore anything else
        const char* known[] = {"forward","back","left","right","stop"};
        for (const char* k : known) {
            if (val == k) {
                strncpy(commandBuf, val.c_str(), sizeof(commandBuf) - 1);
                commandBuf[sizeof(commandBuf) - 1] = '\0';
                commandReady = true;
                return;
            }
        }
        Serial.printf("BLE: ignored unknown write: %s\n", val.c_str());
    }
};

// ── Public API ────────────────────────────────────────────────────────────────

void ble_init() {
    // 1. Override the hardware MAC address before starting BLE
    esp_base_mac_addr_set(custom_base_mac);

    BLEDevice::init("ESP32_UGV");

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    pCtrlChar = pService->createCharacteristic(
        CTRL_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCtrlChar->setCallbacks(new CtrlCallbacks());

    pResultChar = pService->createCharacteristic(
        RESULT_CHAR_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pResultChar->addDescriptor(new BLE2902());

    pService->start();

    BLEAdvertising* pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);
    pAdv->setScanResponse(true);
    BLEDevice::startAdvertising();

    Serial.printf("BLE ready — Spoofing MAC to match Android App: %02X:%02X:%02X:%02X:%02X:%02X\n",
                  custom_base_mac[0], custom_base_mac[1], custom_base_mac[2],
                  custom_base_mac[3], custom_base_mac[4], custom_base_mac[5] + 2);
}

/**
 * Returns true (and fills buf) when a command string has arrived.
 * Call from loop() — non-blocking.
 */
bool ble_receive_command(char* buf, int bufLen) {
    if (!commandReady) return false;
    commandReady = false;
    strncpy(buf, commandBuf, bufLen - 1);
    buf[bufLen - 1] = '\0';
    memset(commandBuf, 0, sizeof(commandBuf));
    return true;
}

void ble_send_result(const char* result) {
    if (pResultChar && deviceConnected) {
        pResultChar->setValue((uint8_t*)result, strlen(result));
        pResultChar->notify();
    }
}