#!/bin/bash
# Usage: repro.sh <out-name>. Cold-starts Jandal debug, runs a chat turn (chat in back stack), then
# fires the widget-mic/ADB voice handoff to Actions while recording the S23U screen.
S=RFCW11265XH
P=ai.jandal.assistant.debug
OUT=$1
TOKEN=$(openssl rand -hex 16)
adb -s $S shell "run-as $P sh -c 'mkdir -p files && printf %s $TOKEN > files/adb_harness_token'"
adb -s $S shell am force-stop $P
adb -s $S shell "am start -n $P/com.kernel.ai.MainActivity --es adb_harness_token $TOKEN --es chat_input 'Remind me about the team lunch'" >/dev/null
sleep 30
adb -s $S shell rm -f /sdcard/$OUT.mp4
adb -s $S shell "screenrecord --time-limit 10 --bit-rate 6000000 /sdcard/$OUT.mp4" &
REC=$!
sleep 3
adb -s $S shell "am start -n $P/com.kernel.ai.MainActivity --es adb_harness_token $TOKEN --es quick_action_input 'Add milk to my shopping list' --ez quick_action_is_voice true" >/dev/null
wait $REC
adb -s $S pull /sdcard/$OUT.mp4 /tmp/r1952/$OUT.mp4
adb -s $S shell rm -f /sdcard/$OUT.mp4
echo done
