#include "motor.h"
#include <Arduino.h>

// L293D input pins — connect to IN1/IN2/IN3/IN4 on the L293D
// EN1 and EN2 on the L293D must be wired HIGH (or to a GPIO held HIGH)
#define IN1 32   // Motor A — direction pin 1
#define IN2 33   // Motor A — direction pin 2
#define IN3 25   // Motor B — direction pin 1
#define IN4 26   // Motor B — direction pin 2

void motor_init() {
    pinMode(IN1, OUTPUT);
    pinMode(IN2, OUTPUT);
    pinMode(IN3, OUTPUT);
    pinMode(IN4, OUTPUT);
    motor_stop();   // safe default state on boot
}

void motor_forward() {
    digitalWrite(IN1, HIGH);
    digitalWrite(IN2, LOW);
    digitalWrite(IN3, HIGH);
    digitalWrite(IN4, LOW);
}

void motor_back() {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, HIGH);
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, HIGH);
}

void motor_left() {
    // Left motor reverses, right motor forward → spins left
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, HIGH);
    digitalWrite(IN3, HIGH);
    digitalWrite(IN4, LOW);
}

void motor_right() {
    // Right motor reverses, left motor forward → spins right
    digitalWrite(IN1, HIGH);
    digitalWrite(IN2, LOW);
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, HIGH);
}

void motor_stop() {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, LOW);
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, LOW);
}
