#!/usr/bin/env bash
#This script is extended by ai, original script don't so pretty
#There no version for windows because i'm lazy, sorry
#Author: MichaAI

set -e  # Остановка при ошибках

# Конфигурационные переменные
PROC_NAME="mindserver"
SERVER_JAR="server-release.jar"
MODS_DIR="config/mods"
BUILD_DIR="build/libs"
DEBUG_PORT=5005
SERVER_URL="https://github.com/Anuken/Mindustry/releases/download/v146/server-release.jar"

# Вывод с цветами для лучшей читаемости
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Функция логирования
log() {
    echo -e "${BLUE}[INFO   ]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR  ]${NC} $1" >&2
}

# Завершение процесса с таймаутом
kill_process() {
    if pgrep -f "$PROC_NAME" > /dev/null; then
        log "Отправка SIGTERM процессу $PROC_NAME..."
        pkill -f "$PROC_NAME"

        # Ждем 5 секунд для корректного завершения
        for i in {1..5}; do
            if ! pgrep -f "$PROC_NAME" > /dev/null; then
                log_success "Процесс $PROC_NAME завершен"
                return 0
            fi
            sleep 1
        done

        # Если процесс все еще жив, используем SIGKILL
        if pgrep -f "$PROC_NAME" > /dev/null; then
            log "Процесс не завершился мягко, отправляем SIGKILL..."
            pkill -9 -f "$PROC_NAME"
            log_success "Процесс $PROC_NAME принудительно завершен"
        fi
    else
        log "Процесс $PROC_NAME не запущен"
    fi
}

# Подготовка директорий
prepare_directories() {
    if [ ! -d "$MODS_DIR" ]; then
        log "Создание директории $MODS_DIR..."
        mkdir -p "$MODS_DIR"
        log_success "Директория $MODS_DIR создана"
    fi
}

# Скачивание JAR файла сервера
download_server_jar() {
    if [ ! -f "$SERVER_JAR" ]; then
        log "Файл сервера $SERVER_JAR не найден, начинаем загрузку..."

        # Проверяем наличие curl или wget
        if command -v curl > /dev/null; then
            log "Загрузка с помощью curl..."
            if curl -L -o "$SERVER_JAR" "$SERVER_URL"; then
                log_success "Файл $SERVER_JAR успешно загружен 📥"
            else
                log_error "Ошибка при загрузке файла $SERVER_JAR ❌"
                exit 1
            fi
        elif command -v wget > /dev/null; then
            log "Загрузка с помощью wget..."
            if wget -O "$SERVER_JAR" "$SERVER_URL"; then
                log_success "Файл $SERVER_JAR успешно загружен 📥"
            else
                log_error "Ошибка при загрузке файла $SERVER_JAR ❌"
                exit 1
            fi
        else
            log_error "Не найдены утилиты curl или wget для загрузки файла"
            log_error "Установите curl или wget и попробуйте снова"
            exit 1
        fi
    else
        log "Файл сервера $SERVER_JAR уже существует ✅"
    fi
}

# Сборка проекта
build_project() {
    log "Сборка проекта с помощью Gradle..."
    if ./gradlew shadowJar; then
        log_success "Сборка проекта завершена успешно"
    else
        log_error "Ошибка при сборке проекта"
        exit 1
    fi
}

# Установка модов
install_mods() {
    log "Перемещение новых JAR файлов из $BUILD_DIR в $MODS_DIR..."
    if [ -z "$(ls -A $BUILD_DIR/*.jar 2>/dev/null)" ]; then
        log_error "JAR файлы не найдены в директории $BUILD_DIR"
        exit 1
    fi

    # Обработка имен файлов с пробелами
    find "$BUILD_DIR" -name "*.jar" -exec mv {} "$MODS_DIR/" \;
    log_success "JAR файлы успешно перемещены в $MODS_DIR"
}

# Запуск сервера
run_server() {
    log "Запуск сервера $SERVER_JAR..."
    java -Dprocname="$PROC_NAME" \
         -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=\*:$DEBUG_PORT \
         -jar "$SERVER_JAR" host
}

# Основная логика
main() {
    log "🚀 Запуск скрипта для $PROC_NAME..."

    kill_process
    prepare_directories
    download_server_jar
    build_project
    install_mods
    run_server
}

# Выполнение основной функции
main
