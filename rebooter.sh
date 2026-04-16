#!/bin/bash

set -e

servers="pvp survival"

sleep 3

while true; do
    echo "Attempting to reboot..."

    for server in $servers; do
        echo "Checking if $server has players..."
        screen -S "$server" -X stuff 'players\n'
        sleep 3
        screen -S "$server" -X hardcopy /tmp/screen_dump.txt
        sleep 3
        line=$(tail -n 2 /tmp/screen_dump.txt | head -n 1)
        echo "Last line: $line"
        echo $line | grep -q "No players are currently in the server." || {
            echo "$server has players, skipping..."
            continue
        }

        echo "Rebooting $server..."
        screen -S "$server" -X stuff 'save foo\n'
        sleep 0.5
        screen -S "$server" -X stuff $'\003'
        sleep 0.5
        screen -S "$server" -X stuff 'java -jar ../server-release.jar load foo\n'
        echo "Rebooted $server"
    done

    sleep 3600 # 1 hour
done
