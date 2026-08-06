/*
 * BandPin — ESP32 firmware
 * ---------------------------------------
 *
 * Two Trill Flex sensors on two separate I2C buses:
 *
 * Strip B:
 *   SDA     -> GPIO 21
 *   SCL     -> GPIO 22
 *   Address -> 0x48
 *   Digits  -> 5–9
 *
 * Strip A:
 *   SDA     -> GPIO 25
 *   SCL     -> GPIO 26
 *   Address -> 0x48
 *   Digits  -> 0–4
 *
 * Both sensors may use address 0x48 because they are connected
 * to separate I2C buses.
 *
 * BLE events:
 *   DOWN
 *   TICK
 *   UP
 *   SELECT
 *   DELETE
 */

#include <Wire.h>
#include <Trill.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>


// ============================================================
// I2C buses
// ============================================================

// Default ESP32 I2C bus for strip B
// SDA = 21, SCL = 22
TwoWire& I2C_B = Wire;

// Second ESP32 I2C bus for strip A
// SDA = 25, SCL = 26
TwoWire I2C_A = TwoWire(1);


// ============================================================
// Pin configuration
// ============================================================

const int STRIP_B_SDA = 21;
const int STRIP_B_SCL = 22;

const int STRIP_A_SDA = 25;
const int STRIP_A_SCL = 26;

const uint32_t I2C_FREQUENCY = 400000;


// ============================================================
// Gesture parameters
// ============================================================

const unsigned long TAP_MAX_MS = 250;

const unsigned long DOUBLE_TAP_GAP_MS = 400;

const unsigned long HOLD_DELETE_MS = 2000;

const unsigned long HOLD_REPEAT_DELETE_MS = 600;

const float ZONE_HYSTERESIS = 0.04f;

const int NUM_ZONES = 5;

const unsigned long SAMPLE_INTERVAL_MS = 10;


// ============================================================
// Active part of Trill Flex
// ============================================================

const float ACTIVE_START = 0.08f;

const float ACTIVE_END = 0.39f;


// ============================================================
// Trill configuration
// ============================================================

// Both sensors use 0x48 because they are on different buses
const uint8_t TRILL_ADDR_A = 0x48;
const uint8_t TRILL_ADDR_B = 0x48;

const int TRILL_PRESCALER = 3;

const int TRILL_NOISE_THRESHOLD = 200;


// ============================================================
// BLE UUIDs
// ============================================================

#define BANDPIN_SERVICE_UUID \
  "4A420001-1000-8000-0080-00805F9B34FB"

#define BANDPIN_CHAR_UUID \
  "4A420002-1000-8000-0080-00805F9B34FB"


// ============================================================
// Event definitions
// ============================================================

enum BandEvent : uint8_t {
  EVT_DOWN = 0,
  EVT_TICK = 1,
  EVT_UP = 2,
  EVT_SELECT = 3,
  EVT_DELETE = 4
};

const char* EVT_NAMES[] = {
  "DOWN",
  "TICK",
  "UP",
  "SELECT",
  "DELETE"
};


// ============================================================
// Strip state
// ============================================================

struct StripState {
  Trill sensor;

  TwoWire* i2cBus = nullptr;

  uint8_t address = 0x48;

  // 0 = A
  // 1 = B
  uint8_t stripIndex = 0;

  // A = 0
  // B = 5
  uint8_t digitOffset = 0;

  bool present = false;

  float locationMax = 3712.0f;


  // Touch tracking
  bool touching = false;

  unsigned long touchStartMs = 0;

  int currentZone = -1;

  float lastPosition = 0.0f;

  bool movedZones = false;

  bool holdFired = false;

  unsigned long lastHoldDeleteMs = 0;


  // Double-tap tracking
  int pendingTapZone = -1;

  unsigned long pendingTapEndMs = 0;
};


StripState stripA;
StripState stripB;


// ============================================================
// BLE variables
// ============================================================

BLEServer* bleServer = nullptr;

BLECharacteristic* eventCharacteristic = nullptr;

volatile bool centralConnected = false;


// ============================================================
// BLE callbacks
// ============================================================

class BandPinServerCallbacks : public BLEServerCallbacks {

  void onConnect(BLEServer* server) override {
    centralConnected = true;

    Serial.println("[BLE] watch connected");
  }


  void onDisconnect(BLEServer* server) override {
    centralConnected = false;

    Serial.println(
      "[BLE] watch disconnected - advertising again");

    server->startAdvertising();
  }
};


// ============================================================
// BLE setup
// ============================================================

void setupBle() {
  BLEDevice::init("BandPin");

  bleServer = BLEDevice::createServer();

  bleServer->setCallbacks(
    new BandPinServerCallbacks());


  BLEService* service =
    bleServer->createService(
      BANDPIN_SERVICE_UUID);


  eventCharacteristic =
    service->createCharacteristic(
      BANDPIN_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);


  eventCharacteristic->addDescriptor(
    new BLE2902());


  service->start();


  BLEAdvertising* advertising =
    BLEDevice::getAdvertising();


  advertising->addServiceUUID(
    BANDPIN_SERVICE_UUID);

  advertising->setScanResponse(true);

  BLEDevice::startAdvertising();


  Serial.println(
    "[BLE] advertising as BandPin");
}


// ============================================================
// Send event to Serial and watch
// ============================================================

void sendEvent(
  uint8_t event,
  StripState& strip,
  int zone,
  float position) {

  /*
   * Reverse zone direction:
   *
   * zone 0 -> digit 4 or 9
   * zone 4 -> digit 0 or 5
   *
   * Strip A:
   * 4, 3, 2, 1, 0
   *
   * Strip B:
   * 9, 8, 7, 6, 5
   */

  uint8_t digit =
    strip.digitOffset + (NUM_ZONES - 1 - zone);


  unsigned long now = millis();


  Serial.printf(
    "[EVT] %-6s strip=%c digit=%d pos=%.2f\n",
    EVT_NAMES[event],
    strip.stripIndex == 0 ? 'A' : 'B',
    digit,
    position);


  if (
    !centralConnected || eventCharacteristic == nullptr) {
    return;
  }


  uint8_t payload[6] = {
    event,

    strip.stripIndex,

    digit,

    static_cast<uint8_t>(
      constrain(
        static_cast<int>(position * 100.0f),
        0,
        100)),

    static_cast<uint8_t>(
      (now >> 8) & 0xFF),

    static_cast<uint8_t>(
      now & 0xFF)
  };


  eventCharacteristic->setValue(
    payload,
    sizeof(payload));

  eventCharacteristic->notify();
}


// ============================================================
// Setup one Trill Flex sensor
// ============================================================

bool setupStrip(
  StripState& strip,
  TwoWire* i2cBus,
  uint8_t address,
  uint8_t stripIndex,
  int retries) {

  strip.i2cBus = i2cBus;

  strip.address = address;

  strip.stripIndex = stripIndex;

  strip.digitOffset =
    stripIndex * NUM_ZONES;


  int result = -1;


  for (
    int attempt = 0;
    attempt < retries;
    attempt++) {

    result = strip.sensor.setup(
      Trill::TRILL_FLEX,
      address,
      i2cBus);


    if (result == 0) {
      break;
    }


    Serial.printf(
      "[Trill] strip %c not found, attempt %d/%d\n",
      stripIndex == 0 ? 'A' : 'B',
      attempt + 1,
      retries);


    delay(300);
  }


  if (result != 0) {
    strip.present = false;

    return false;
  }


  strip.sensor.setMode(
    Trill::CENTROID);

  delay(10);


  strip.sensor.setPrescaler(
    TRILL_PRESCALER);

  delay(10);


  strip.sensor.setNoiseThreshold(
    TRILL_NOISE_THRESHOLD);

  delay(10);


  strip.sensor.updateBaseline();

  delay(100);


  int numChannels =
    strip.sensor.getNumChannels();


  if (numChannels > 1) {
    strip.locationMax =
      (numChannels - 1) * 128.0f;
  }


  strip.present = true;


  Serial.printf(
    "[Trill] strip %c initialized, channels=%d, locationMax=%.1f\n",
    stripIndex == 0 ? 'A' : 'B',
    numChannels,
    strip.locationMax);


  return true;
}


// ============================================================
// Zone classification
// ============================================================

int classifyZone(
  float position,
  int currentZone) {

  if (currentZone >= 0) {

    float lowerBoundary =
      static_cast<float>(currentZone) / NUM_ZONES - ZONE_HYSTERESIS;


    float upperBoundary =
      static_cast<float>(currentZone + 1) / NUM_ZONES + ZONE_HYSTERESIS;


    if (
      position >= lowerBoundary && position < upperBoundary) {
      return currentZone;
    }
  }


  int zone =
    static_cast<int>(
      position * NUM_ZONES);


  return constrain(
    zone,
    0,
    NUM_ZONES - 1);
}


// ============================================================
// Process one strip
// ============================================================

void processStrip(
  StripState& strip) {

  if (!strip.present) {
    return;
  }


  strip.sensor.read();


  bool touched =
    strip.sensor.getNumTouches() > 0;


  float position =
    strip.lastPosition;


  if (touched) {

    float rawPosition =
      strip.sensor.touchLocation(0) / strip.locationMax;


    rawPosition = constrain(
      rawPosition,
      0.0f,
      1.0f);


    /*
     * Ignore touches after the active region.
     *
     * The currently used part of the strip is:
     * 0.08 to 0.39
     */

    if (
      rawPosition < ACTIVE_START || rawPosition > ACTIVE_END) {

      touched = false;

    } else {

      /*
       * Convert active range 0.08–0.39
       * into normalized range 0.0–1.0
       */

      position =
        (rawPosition - ACTIVE_START) / (ACTIVE_END - ACTIVE_START);


      position = constrain(
        position,
        0.0f,
        1.0f);


      strip.lastPosition = position;
    }
  }


  unsigned long now = millis();


  // Expire old first tap
  if (
    strip.pendingTapZone >= 0 && !strip.touching && now - strip.pendingTapEndMs > DOUBLE_TAP_GAP_MS) {

    strip.pendingTapZone = -1;
  }


  // ==========================================================
  // Finger is touching
  // ==========================================================

  if (touched) {

    // New finger contact
    if (!strip.touching) {

      strip.touching = true;

      strip.touchStartMs = now;

      strip.currentZone =
        classifyZone(
          position,
          -1);

      strip.movedZones = false;

      strip.holdFired = false;

      strip.lastHoldDeleteMs = 0;


      sendEvent(
        EVT_DOWN,
        strip,
        strip.currentZone,
        position);

    } else {

      // Existing contact: check sliding
      int newZone =
        classifyZone(
          position,
          strip.currentZone);


      if (newZone != strip.currentZone) {

        strip.currentZone = newZone;

        strip.movedZones = true;

        // Sliding cancels double tap
        strip.pendingTapZone = -1;


        sendEvent(
          EVT_TICK,
          strip,
          strip.currentZone,
          position);
      }


      // Hold to delete
      if (
        !strip.movedZones && now - strip.touchStartMs >= HOLD_DELETE_MS) {

        bool firstDelete =
          !strip.holdFired;


        bool repeatDelete =
          strip.holdFired && now - strip.lastHoldDeleteMs >= HOLD_REPEAT_DELETE_MS;


        if (
          firstDelete || repeatDelete) {

          strip.holdFired = true;

          strip.lastHoldDeleteMs = now;

          strip.pendingTapZone = -1;


          sendEvent(
            EVT_DELETE,
            strip,
            strip.currentZone,
            position);
        }
      }
    }

  }


  // ==========================================================
  // Finger lifted
  // ==========================================================

  else if (strip.touching) {

    strip.touching = false;


    unsigned long duration =
      now - strip.touchStartMs;


    sendEvent(
      EVT_UP,
      strip,
      strip.currentZone,
      strip.lastPosition);


    bool isTap =
      !strip.movedZones && !strip.holdFired && duration <= TAP_MAX_MS;


    if (isTap) {

      bool secondTapSameZone =
        strip.pendingTapZone == strip.currentZone;


      bool secondTapInTime =
        strip.touchStartMs - strip.pendingTapEndMs <= DOUBLE_TAP_GAP_MS;


      if (
        secondTapSameZone && secondTapInTime) {

        // Double tap -> SELECT

        strip.pendingTapZone = -1;


        sendEvent(
          EVT_SELECT,
          strip,
          strip.currentZone,
          strip.lastPosition);

      } else {

        // First tap

        strip.pendingTapZone =
          strip.currentZone;

        strip.pendingTapEndMs = now;
      }

    } else {

      strip.pendingTapZone = -1;
    }
  }
}


// ============================================================
// Wait until a sensor is connected
// ============================================================

void waitForStrip(
  StripState& strip,
  TwoWire* bus,
  uint8_t address,
  uint8_t stripIndex) {

  char stripName =
    stripIndex == 0 ? 'A' : 'B';


  while (
    !setupStrip(
      strip,
      bus,
      address,
      stripIndex,
      1)) {

    Serial.printf(
      "[BandPin] waiting for strip %c...\n",
      stripName);

    delay(1000);
  }
}


bool stripIsConnected(
  TwoWire* bus,
  uint8_t address) {
  bus->beginTransmission(address);

  return bus->endTransmission() == 0;
}

// ============================================================
// Arduino setup
// ============================================================

void setup() {

  Serial.begin(115200);

  delay(1000);


  Serial.println();

  Serial.println(
    "[BandPin] booting...");


  // ----------------------------------------------------------
  // Start I2C bus B
  // ----------------------------------------------------------

  bool busBStarted = I2C_B.begin(
    STRIP_B_SDA,
    STRIP_B_SCL,
    I2C_FREQUENCY);


  if (busBStarted) {
    Serial.println(
      "[I2C] bus B started: SDA=21 SCL=22");
  } else {
    Serial.println(
      "[I2C] ERROR: bus B could not start");
  }


  // ----------------------------------------------------------
  // Start I2C bus A
  // ----------------------------------------------------------

  bool busAStarted = I2C_A.begin(
    STRIP_A_SDA,
    STRIP_A_SCL,
    I2C_FREQUENCY);


  if (busAStarted) {
    Serial.println(
      "[I2C] bus A started: SDA=25 SCL=26");
  } else {
    Serial.println(
      "[I2C] ERROR: bus A could not start");
  }


  delay(100);


  // ----------------------------------------------------------
  // Initialize strip B
  // GPIO 21 and 22
  // Digits 5–9
  // ----------------------------------------------------------

  Serial.println(
    "[BandPin] searching for strip B on GPIO 21/22...");


  if (
    setupStrip(
      stripB,
      &I2C_B,
      TRILL_ADDR_B,
      1,
      3)) {
    Serial.println(
      "[BandPin] strip B ready: digits 5-9");


  } else {
    Serial.println(
      "[BandPin] strip B absent; continuing with strip A only");
  }

  // ----------------------------------------------------------
  // Initialize strip A
  // GPIO 25 and 26
  // Digits 0–4
  // ----------------------------------------------------------

  Serial.println(
    "[BandPin] searching for strip A on GPIO 25/26...");


  if (
    setupStrip(
      stripA,
      &I2C_A,
      TRILL_ADDR_A,
      0,
      3)) {

    Serial.println(
      "[BandPin] strip A ready: digits 0-4");

  } else {

    Serial.println(
      "[BandPin] strip A absent; continuing without it");
  }

  // ----------------------------------------------------------
  // BLE
  // ----------------------------------------------------------

  setupBle();


  Serial.println(
    "[BandPin] both strips ready");

  Serial.println(
    "[BandPin] touch either strip");
}




// ============================================================
// Arduino loop
// ============================================================

void loop() {

  static unsigned long lastSampleMs = 0;
  static unsigned long lastStripBCheckMs = 0;
  static unsigned long lastStripACheckMs = 0;

  unsigned long now = millis();


  // ----------------------------------------------------------
  // Hot-detect strip B
  // ----------------------------------------------------------

  if (
    now - lastStripBCheckMs >= 1000) {

    lastStripBCheckMs = now;

    bool connected =
      stripIsConnected(
        &I2C_B,
        TRILL_ADDR_B);


    // Strip B was disconnected
    if (
      stripB.present && !connected) {

      stripB.present = false;

      stripB.touching = false;
      stripB.currentZone = -1;
      stripB.pendingTapZone = -1;
      stripB.holdFired = false;

      Serial.println(
        "[BandPin] strip B disconnected; continuing with strip A");
    }


    // Strip B was connected or reconnected
    else if (
      !stripB.present && connected) {

      Serial.println(
        "[BandPin] strip B detected; initializing...");

      if (
        setupStrip(
          stripB,
          &I2C_B,
          TRILL_ADDR_B,
          1,
          3)) {

        Serial.println(
          "[BandPin] strip B restored: digits 5-9");
      }
    }
  }


  // ----------------------------------------------------------
  // Hot-detect strip A
  // ----------------------------------------------------------

  if (now - lastStripACheckMs >= 1000) {

    lastStripACheckMs = now;

    bool connected =
      stripIsConnected(
        &I2C_A,
        TRILL_ADDR_A);

    if (stripA.present && !connected) {

      stripA.present = false;
      stripA.touching = false;
      stripA.currentZone = -1;
      stripA.pendingTapZone = -1;
      stripA.holdFired = false;

      Serial.println(
        "[BandPin] strip A disconnected; continuing without it");

    } else if (!stripA.present && connected) {

      Serial.println(
        "[BandPin] strip A detected; initializing...");

      if (
        setupStrip(
          stripA,
          &I2C_A,
          TRILL_ADDR_A,
          0,
          3)) {

        Serial.println(
          "[BandPin] strip A restored: digits 0-4");
      }
    }
  }


  // ----------------------------------------------------------
  // Sensor sampling
  // ----------------------------------------------------------

  if (
    now - lastSampleMs < SAMPLE_INTERVAL_MS) {

    delay(1);

    return;
  }


  lastSampleMs = now;


  if (stripA.present) {
    processStrip(stripA);
  }

  if (stripB.present) {
    processStrip(stripB);
  }
}