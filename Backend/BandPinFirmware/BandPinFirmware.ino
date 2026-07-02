/*
 * BandPin — ESP32 firmware (Arduino C++)
 * ---------------------------------------
 * Reads up to two Trill Flex strips over I²C, runs the BandPin gesture
 * engine (slide ticks / double-tap select / hold delete) and pushes
 * high-level events to the Galaxy Watch 4 over BLE GATT NOTIFY.
 *
 * Digit layout (fixed, per the BandPin proposal):
 *   Strip A (top of wrist,  I²C 0x48): digits 0–4, zone 0 at the strip start
 *   Strip B (palm side,     I²C 0x49): digits 5–9, zone 0 at the strip start
 *
 * Strip B needs its I²C address changed to 0x49 via the solder jumpers on
 * the back of the Trill board (Flex address range is 0x48–0x4F). The
 * firmware boots fine with only strip A connected — strip B is optional
 * and hot-checked at boot.
 *
 * Gesture vocabulary (events sent over BLE):
 *   DOWN    finger lands on a strip (zone included, no haptic on watch)
 *   TICK    finger crossed a zone boundary while sliding → watch vibrates
 *   UP      finger lifted
 *   SELECT  double-tap in a zone → digit chosen
 *   DELETE  hold ≥ 2 s without sliding → remove last digit
 *
 * BLE payload (6 bytes):
 *   [0] event      0=DOWN 1=TICK 2=UP 3=SELECT 4=DELETE
 *   [1] strip      0=A 1=B
 *   [2] digit      0–9 (zone + strip offset)
 *   [3] position   0–100, position along the whole strip
 *   [4] millis() >> 8   (low 16 bits of board time, for latency analysis)
 *   [5] millis() & 0xFF
 *
 * Wiring (both strips share the bus):
 *   SDA → GPIO 21, SCL → GPIO 22, VCC → 3V3, GND → GND
 *
 * Dependencies (Arduino IDE):
 *   - ESP32 board package (Tools → Board → ESP32 Dev Module)
 *   - "Trill" library by Bela (Library Manager)
 */

#include <Wire.h>
#include <Trill.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── Tunable gesture parameters (pilot-test these!) ──────────────────────────
const unsigned long TAP_MAX_MS        = 250;   // max contact time to count as a tap
const unsigned long DOUBLE_TAP_GAP_MS = 400;   // max pause between the two taps
const unsigned long HOLD_DELETE_MS    = 2000;  // hold this long (no sliding) = delete
const float         ZONE_HYSTERESIS   = 0.04;  // fraction of strip, anti-flicker margin
const int           NUM_ZONES         = 5;     // 5 digits per strip
const unsigned long SAMPLE_INTERVAL_MS = 10;   // 100 Hz sensor loop

// ── Trill setup (same values as the validated raw-data test sketch) ─────────
const uint8_t TRILL_ADDR_A = 0x48;   // strip A — factory default
const uint8_t TRILL_ADDR_B = 0x49;   // strip B — set via solder jumper
const int     TRILL_PRESCALER      = 3;
const int     TRILL_NOISE_THRESHOLD = 200;

// ── BLE UUIDs (must match BandPinWatch app + shared/constants.py) ───────────
#define BANDPIN_SERVICE_UUID "4A420001-1000-8000-0080-00805F9B34FB"
#define BANDPIN_CHAR_UUID    "4A420002-1000-8000-0080-00805F9B34FB"

// Event codes
enum BandEvent : uint8_t {
  EVT_DOWN   = 0,
  EVT_TICK   = 1,
  EVT_UP     = 2,
  EVT_SELECT = 3,
  EVT_DELETE = 4,
};
const char* EVT_NAMES[] = {"DOWN", "TICK", "UP", "SELECT", "DELETE"};

// ── Per-strip gesture state machine ─────────────────────────────────────────
struct StripState {
  Trill        sensor;
  uint8_t      address;
  uint8_t      stripIndex;    // 0 = A, 1 = B
  uint8_t      digitOffset;   // A → 0, B → 5
  bool         present = false;
  float        locationMax = 3712.0f;  // (numChannels-1) * 128, refined at boot

  // touch tracking
  bool          touching      = false;
  unsigned long touchStartMs  = 0;
  int           currentZone   = -1;
  float         lastPosition  = 0.0f;
  bool          movedZones    = false;  // slid across a boundary → not a tap
  bool          holdFired     = false;  // DELETE already sent for this contact

  // double-tap tracking
  int           pendingTapZone  = -1;
  unsigned long pendingTapEndMs = 0;
};

StripState stripA;
StripState stripB;

// ── BLE plumbing ─────────────────────────────────────────────────────────────
BLEServer*         bleServer         = nullptr;
BLECharacteristic* eventCharacteristic = nullptr;
volatile bool      centralConnected  = false;

class BandPinServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    centralConnected = true;
    Serial.println("[BLE] watch connected");
  }
  void onDisconnect(BLEServer* server) override {
    centralConnected = false;
    Serial.println("[BLE] watch disconnected — advertising again");
    server->startAdvertising();
  }
};

void setupBle() {
  BLEDevice::init("BandPin");
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new BandPinServerCallbacks());

  BLEService* service = bleServer->createService(BANDPIN_SERVICE_UUID);
  eventCharacteristic = service->createCharacteristic(
      BANDPIN_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  eventCharacteristic->addDescriptor(new BLE2902());
  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BANDPIN_SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[BLE] advertising as 'BandPin'");
}

void sendEvent(uint8_t event, StripState& strip, int zone, float position) {
  uint8_t digit = strip.digitOffset + zone;
  unsigned long now = millis();

  Serial.printf("[EVT] %-6s strip=%c digit=%d pos=%.2f\n",
                EVT_NAMES[event], strip.stripIndex == 0 ? 'A' : 'B',
                digit, position);

  if (!centralConnected || eventCharacteristic == nullptr) return;

  uint8_t payload[6] = {
      event,
      strip.stripIndex,
      digit,
      (uint8_t)constrain((int)(position * 100.0f), 0, 100),
      (uint8_t)((now >> 8) & 0xFF),
      (uint8_t)(now & 0xFF),
  };
  eventCharacteristic->setValue(payload, sizeof(payload));
  eventCharacteristic->notify();
}

// ── Trill helpers ────────────────────────────────────────────────────────────
bool setupStrip(StripState& strip, uint8_t address, uint8_t stripIndex,
                int retries) {
  strip.address     = address;
  strip.stripIndex  = stripIndex;
  strip.digitOffset = stripIndex * NUM_ZONES;

  int ret = -1;
  for (int attempt = 0; attempt < retries; attempt++) {
    ret = strip.sensor.setup(Trill::TRILL_FLEX, address);
    if (ret == 0) break;
    delay(300);
  }
  if (ret != 0) {
    strip.present = false;
    return false;
  }

  strip.sensor.setMode(Trill::CENTROID);
  delay(10);
  strip.sensor.setPrescaler(TRILL_PRESCALER);
  delay(10);
  strip.sensor.setNoiseThreshold(TRILL_NOISE_THRESHOLD);
  delay(10);
  strip.sensor.updateBaseline();
  delay(100);

  int numChannels = strip.sensor.getNumChannels();
  if (numChannels > 1) {
    strip.locationMax = (numChannels - 1) * 128.0f;
  }

  strip.present = true;
  return true;
}

// Zone classification with hysteresis: sticky inside the current zone until
// the finger is clearly past the boundary.
int classifyZone(float position, int currentZone) {
  if (currentZone >= 0) {
    float lo = (float)currentZone / NUM_ZONES - ZONE_HYSTERESIS;
    float hi = (float)(currentZone + 1) / NUM_ZONES + ZONE_HYSTERESIS;
    if (position >= lo && position < hi) return currentZone;
  }
  int zone = (int)(position * NUM_ZONES);
  return constrain(zone, 0, NUM_ZONES - 1);
}

// ── Gesture engine — runs once per sample per strip ─────────────────────────
void processStrip(StripState& strip) {
  if (!strip.present) return;

  strip.sensor.read();
  bool  touched  = strip.sensor.getNumTouches() > 0;
  float position = strip.lastPosition;
  if (touched) {
    position = strip.sensor.touchLocation(0) / strip.locationMax;
    position = constrain(position, 0.0f, 1.0f);
    strip.lastPosition = position;
  }

  unsigned long now = millis();

  // Expire a stale first tap of a would-be double-tap
  if (strip.pendingTapZone >= 0 &&
      !strip.touching &&
      now - strip.pendingTapEndMs > DOUBLE_TAP_GAP_MS) {
    strip.pendingTapZone = -1;
  }

  if (touched) {
    if (!strip.touching) {
      // ── finger down ──
      strip.touching     = true;
      strip.touchStartMs = now;
      strip.currentZone  = classifyZone(position, -1);
      strip.movedZones   = false;
      strip.holdFired    = false;
      sendEvent(EVT_DOWN, strip, strip.currentZone, position);
    } else {
      // ── sliding? ──
      int zone = classifyZone(position, strip.currentZone);
      if (zone != strip.currentZone) {
        strip.currentZone = zone;
        strip.movedZones  = true;
        strip.pendingTapZone = -1;  // sliding cancels double-tap
        sendEvent(EVT_TICK, strip, zone, position);
      }
      // ── hold to delete ──
      if (!strip.holdFired && !strip.movedZones &&
          now - strip.touchStartMs >= HOLD_DELETE_MS) {
        strip.holdFired = true;
        strip.pendingTapZone = -1;
        sendEvent(EVT_DELETE, strip, strip.currentZone, position);
      }
    }
  } else if (strip.touching) {
    // ── finger up ──
    strip.touching = false;
    unsigned long duration = now - strip.touchStartMs;
    sendEvent(EVT_UP, strip, strip.currentZone, strip.lastPosition);

    bool isTap = !strip.movedZones && !strip.holdFired &&
                 duration <= TAP_MAX_MS;
    if (isTap) {
      if (strip.pendingTapZone == strip.currentZone &&
          strip.touchStartMs - strip.pendingTapEndMs <= DOUBLE_TAP_GAP_MS) {
        // second tap in the same zone → digit selected
        strip.pendingTapZone = -1;
        sendEvent(EVT_SELECT, strip, strip.currentZone, strip.lastPosition);
      } else {
        // first tap — wait for a possible second one
        strip.pendingTapZone  = strip.currentZone;
        strip.pendingTapEndMs = now;
      }
    } else {
      strip.pendingTapZone = -1;
    }
  }
}

// ── Arduino entry points ─────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n[BandPin] booting…");

  Wire.begin(21, 22);

  // Strip A is required — keep retrying so a loose cable is obvious
  if (!setupStrip(stripA, TRILL_ADDR_A, 0, 20)) {
    Serial.println("[BandPin] FATAL: strip A (0x48) not found. Check wiring.");
    while (true) {
      Serial.println("[BandPin] waiting for strip A…");
      if (setupStrip(stripA, TRILL_ADDR_A, 0, 1)) break;
      delay(1000);
    }
  }
  Serial.println("[BandPin] strip A ready (digits 0-4)");

  // Strip B is optional — single quick probe
  if (setupStrip(stripB, TRILL_ADDR_B, 1, 2)) {
    Serial.println("[BandPin] strip B ready (digits 5-9)");
  } else {
    Serial.println("[BandPin] strip B (0x49) not found — running single-strip"
                   " (digits 0-4 only)");
  }

  setupBle();
  Serial.println("[BandPin] ready. Touch the strip.");
}

void loop() {
  static unsigned long lastSampleMs = 0;
  unsigned long now = millis();
  if (now - lastSampleMs < SAMPLE_INTERVAL_MS) {
    delay(1);
    return;
  }
  lastSampleMs = now;

  processStrip(stripA);
  processStrip(stripB);
}
