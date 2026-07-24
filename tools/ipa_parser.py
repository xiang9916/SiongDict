#!/usr/bin/env python3
"""
IPA parser for Xiang dialect pronunciations.

Splits an IPA syllable into (initial, final, tone).
Handles common Xiang dialect features: aspirated consonants, nasalized vowels,
tone digits 0-8 with modifiers (-, =, a, b, etc.), and multi-syllable entries.
"""

import re

# Ordered initial patterns (longest first to avoid partial matches)
INITIALS = [
    # Voiced aspirated (MCPDict-specific with ʱ)
    r"dzʱ", r"tsʱ", r"tʂʱ", r"tɕʱ", r"tʃʱ",
    r"dʑʱ", r"dzʱ",
    r"pʱ", r"tʱ", r"kʱ", r"bʱ", r"dʱ", r"gʱ",
    r"vʱ", r"zʱ", r"ɣʱ",
    # Aspirated with ʰ
    r"dzʰ", r"tsʰ", r"tʂʰ", r"tɕʰ", r"tʃʰ", r"tʒʰ",
    r"pʰ", r"tʰ", r"kʰ", r"bʰ", r"dʰ", r"gʰ",
    r"fʰ", r"sʰ", r"xʰ", r"vʰ", r"zʰ",
    r"m̥", r"n̥", r"l̥", r"ŋ̊",
    # Affricates
    r"tɕ", r"tʂ", r"tʃ", r"ts", r"tʒ", r"dʐ", r"dʑ", r"dz",
    # Fricatives
    r"ɕ", r"ʂ", r"ʃ", r"ʒ", r"ç", r"ɸ", r"β", r"θ", r"ð",
    r"f", r"s", r"x", r"h", r"ɣ", r"v", r"z", r"ʐ", r"ʑ", r"j",
    # Nasals
    r"ȵ", r"ɲ", r"ŋ", r"m", r"n",
    # Liquids and approximants
    r"l", r"r", r"ɻ", r"ɹ", r"w", r"ɰ",
    # Stops
    r"p", r"t", r"k", r"b", r"d", r"g", r"ɡ", r"ʔ",
    # Voiced retroflex stops (MCPDict-specific)
    r"ȶ", r"ȡ",
]

INITIAL_RE = re.compile(r"^(" + "|".join(INITIALS) + r")")

# Tone: digit 0-8 followed by optional modifiers
TONE_RE = re.compile(r"([0-8])([a-z=?\-]*)$")


def parse_syllable(syll):
    """Parse a single IPA syllable into (initial, final, tone_str).

    Returns None if the syllable cannot be parsed.
    """
    syll = syll.strip()
    if not syll:
        return None

    tone_match = TONE_RE.search(syll)
    if tone_match:
        tone_str = syll[tone_match.start():]
        body = syll[:tone_match.start()]
    else:
        tone_str = ""
        body = syll

    if not body:
        if tone_str:
            return ("", "", tone_str)
        return None

    initial = ""
    m = INITIAL_RE.match(body)
    if m:
        initial = m.group(1)
        final = body[m.end():]
    else:
        final = body

    return (initial, final, tone_str)


def parse_ipa(ipa_str):
    """Parse an IPA string that may contain multiple variants.

    Returns a list of (initial, final, tone) tuples, one per syllable.
    If the string contains variant readings (separated by /), only the
    first variant is parsed.

    Returns empty list if nothing could be parsed.
    """
    if not ipa_str:
        return []

    ipa_str = ipa_str.split("/")[0].strip()
    if not ipa_str:
        return []

    result = parse_syllable(ipa_str)
    if result:
        return [result]

    return []


def get_final_core(final_str):
    """Extract the core vowel from a final, stripping nasals and glides.

    e.g. 'iɑ' -> 'iɑ', 'aŋ' -> 'a', 'iae' -> 'iae'
    """
    if not final_str:
        return ""
    core = re.sub(r"[ŋɲmnɴ]+$", "", final_str)
    core = re.sub(r"[̃ᵑⁿ]+$", "", core)
    core = re.sub(r"[ʷʲ]+$", "", core)
    return core


def get_tone_category(tone_str):
    """Normalize tone string to a category digit (0-8)."""
    if not tone_str:
        return ""
    m = re.match(r"([0-8])", tone_str)
    return m.group(1) if m else ""
