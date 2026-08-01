import time

# Каждый элемент полностью описывает один кадр:
# (маска видимых секторов, яркость 0.0–1.0, длительность в миллисекундах)
#
# Биты маски соответствуют шести секторам:
# 0b111111 — видны все
# 0b000000 — не виден ни один
# 0b001001 — видны отдельные сектора
#
# Сейчас яркость всех видимых кадров равна 1.0 (100%).
FRAMES = [
    # Первая слабая попытка
    (0b000100, 0.3, 20),
    (0b000000, 0.0, 110),

    # Вторая, чуть увереннее
    (0b101001, 0.45, 25),
    (0b000000, 0.0, 90),

    # Первая полная, но тусклая вспышка
    (0b111111, 0.65, 30),
    (0b000000, 0.0, 100),

    # Секторы дёргаются вразнобой перед финалом
    (0b110110, 0.5, 20),
    (0b011011, 0.6, 20),
    (0b000000, 0.0, 60),

    # Почти зажглась — и тут же короткий провал
    (0b111111, 0.9, 40),
    (0b000000, 0.0, 45),

    # Финальный быстрый разгон
    (0b111111, 0.75, 25),
    (0b111111, 1.0, 25),

    # Устойчиво включено
    (0b111111, 1.0, 170),
]

SECTOR_COUNT = 6


def frame_text(mask: int, brightness: float) -> str:
    """Возвращает шесть терминальных символов — по одному на сектор."""
    if brightness <= 0.0:
        visible_char = " "
    elif brightness < 0.35:
        visible_char = "░"
    elif brightness < 0.70:
        visible_char = "▒"
    elif brightness < 1.0:
        visible_char = "▓"
    else:
        visible_char = "█"

    return " ".join(
        visible_char if mask & (1 << index) else "·"
        for index in range(SECTOR_COUNT)
    )


def validate_frames() -> None:
    for index, (mask, brightness, hold_ms) in enumerate(FRAMES, start=1):
        if not 0 <= mask < (1 << SECTOR_COUNT):
            raise ValueError(f"Кадр {index}: неверная маска {mask:b}")

        if not 0.0 <= brightness <= 1.0:
            raise ValueError(
                f"Кадр {index}: яркость должна быть от 0.0 до 1.0"
            )

        if hold_ms < 0:
            raise ValueError(
                f"Кадр {index}: длительность не может быть отрицательной"
            )


def main() -> None:
    validate_frames()

    total_ms = sum(hold_ms for _, _, hold_ms in FRAMES)

    print(f"Кадров: {len(FRAMES)} | цикл: {total_ms} мс")
    print("Ctrl+C — остановить\n")

    try:
        while True:
            for index, (mask, brightness, hold_ms) in enumerate(FRAMES, start=1):
                picture = frame_text(mask, brightness)

                print(
                    f"\r{picture}   "
                    f"кадр {index}/{len(FRAMES)}   "
                    f"яркость={brightness:.2f}   "
                    f"hold={hold_ms:>3} мс   ",
                    end="",
                    flush=True,
                )

                time.sleep(hold_ms / 1000)

    except KeyboardInterrupt:
        print("\nОстановлено.")


if __name__ == "__main__":
    main()