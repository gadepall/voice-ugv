#pragma once

// Simplified header — audio streaming functions removed.
// The phone now handles all voice recognition and sends command strings only.

void ble_init();
bool ble_receive_command(char* buf, int bufLen);
void ble_send_result(const char* result);
