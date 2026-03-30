#!/bin/bash
set -e


VERSION=$1

if [ "$2" != "" ]; then
    (cd ../tmp/Mindustry/ && git pull && ./gradlew desktop:dist)
    cp ../tmp/Mindustry/core/assets/sprites/block_colors.png src/main/resources/block_colors.png

    echo "Building the plugin"
    ./gradlew shadowJar

    echo "Copying the plugin to the server"
    scp rebooter.sh $2:/root/
    scp build/libs/tws-plugin-return-all.jar $2:/root/
    scp $0 $2:/root/
    ssh $2 "chmod +x version-bump.sh; ./version-bump.sh $VERSION"
    exit
fi

if [ ! -z "$VERSION" ]; then
    echo "Updating the version in the server"
    rm -f server-release.jar
    wget https://github.com/Anuken/Mindustry/releases/download/$VERSION/server-release.jar
fi

reboot-server() {
    dir=$1

    echo "Updating $dir plugin"
    cp /root/tws-plugin-return-all.jar /root/$dir/config/mods/tws-plugin-return-all.jar

    echo "Rebooting $1"
    screen -S "$dir" -X stuff 'save foo\n'
    sleep 0.5
    screen -S "$dir" -X stuff $'\003'
    sleep 0.5
    screen -S "$dir" -X stuff 'java -jar ../server-release.jar load foo\n'
}

reboot-server pvp
reboot-server survival
systemctl restart rebooter

echo "Done"
