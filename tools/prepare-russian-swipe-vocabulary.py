#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Order Russian word rows for trie locality; preserve all bytes and same-path chronology."""
import argparse
from pathlib import Path

LETTERS = frozenset("абвгдежзийклмнопрстуфхцчшщыьэюя")


def swipe_path(row):
    text = row.lstrip(b" \t")
    if not text.startswith(b"word=") or b"," not in text:
        return None
    path = []
    for char in text[5:text.index(b",")].decode("utf-8"):
        cp = ord(char)
        if 0x410 <= cp <= 0x42F:
            char = chr(cp + 0x20)
        elif cp == 0x401:
            char = "ё"
        char = {"ё": "е", "ъ": "х"}.get(char, char)
        if char in LETTERS:
            path.append(char)
        elif char not in "´‘’" and (char.isascii() and char.isalpha() or 0x7F < ord(char) < 0xFFFD):
            return ""
    return "".join(path)


def prepare(data):
    rows = data.splitlines(keepends=True)
    words = iter(sorted((row for row in rows if swipe_path(row) is not None), key=swipe_path))
    return b"".join(next(words) if swipe_path(row) is not None else row for row in rows)


def self_test():
    source = "dictionary=main\nword=Я,f=1\nword=всё,f=10\nword=все,f=20\nword=из-за,f=30\nword=Изза,f=40\n".encode()
    ordered = prepare(source)
    assert sorted(source.splitlines(keepends=True)) == sorted(ordered.splitlines(keepends=True))
    assert ordered.index("всё".encode()) < ordered.index("все".encode())
    assert swipe_path("word=Объяснить,f=1\n".encode()) == "обхяснить"
    assert swipe_path("word=из-за,f=1\n".encode()) == swipe_path("word=Изза,f=1\n".encode())
    assert prepare(ordered) == ordered


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("source", nargs="?", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif not args.source or not args.output or args.source.resolve() == args.output.resolve():
        parser.error("Use distinct source and output files.")
    else:
        args.output.write_bytes(prepare(args.source.read_bytes()))
