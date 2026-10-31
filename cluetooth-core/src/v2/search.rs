//! SPEC Q-1: the `data.js` `matchDevice` rule, mirrored exactly.

/// JS `String.prototype.trim`: the WhiteSpace and LineTerminator code points. Rust's `char::is_whitespace` differs
/// (it includes U+0085, and Java's includes U+001C..U+001F), so the set is explicit.
fn is_js_space(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n' | '\u{0b}' | '\u{0c}' | '\r' | ' ' | '\u{a0}' | '\u{1680}' | '\u{2000}'
            ..='\u{200a}'
                | '\u{2028}'
                | '\u{2029}'
                | '\u{202f}'
                | '\u{205f}'
                | '\u{3000}'
                | '\u{feff}'
    )
}

fn js_trim(text: &str) -> &str {
    text.trim_matches(is_js_space)
}

fn strip_separators_lower(text: &str) -> String {
    text.chars()
        .filter(|&c| c != ':' && c != '-')
        .collect::<String>()
        .to_lowercase()
}

/// A blank query is no search (Q-1, Q-2).
pub(crate) fn is_blank(query: &str) -> bool {
    js_trim(query).is_empty()
}

/// Q-1: the trimmed query matches the name case-insensitively as a substring, or, only when it contains a digit,
/// the address with ":" and "-" dropped on both sides. A blank query and a missing name never match by name.
pub fn matches(query: &str, name: Option<&str>, addr: &str) -> bool {
    let query = js_trim(query);
    if query.is_empty() {
        return false;
    }
    if name.is_some_and(|name| name.to_lowercase().contains(&query.to_lowercase())) {
        return true;
    }
    if !query.chars().any(|c| c.is_ascii_digit()) {
        return false;
    }
    strip_separators_lower(addr).contains(&strip_separators_lower(query))
}

#[cfg(test)]
mod tests {
    use super::matches;

    /// `docs/v2-api.md` §2.11, from the prototype gallery (SPEC Q-1).
    #[test]
    fn q1_gallery_vectors() {
        let vectors: &[(&str, Option<&str>, &str, bool)] = &[
            ("qt", Some("QT-Sensor"), "AA:BB:CC:00:11:22", true),
            ("qt", Some("SmartQt"), "AA:BB:CC:00:11:22", true),
            ("qt", Some("aqtb"), "AA:BB:CC:00:11:22", true),
            ("qt", Some("Other"), "AA:BB:CC:00:11:22", false),
            ("4b4b", None, "72:CD:4C:4B:4B:6F", true),
            ("4B:4B", None, "72:CD:4C:4B:4B:6F", true),
            ("4b-4b", None, "72:CD:4C:4B:4B:6F", true),
            ("7d3f", None, "40:E7:7D:3F:7A:CD", true),
            ("ab", Some("Sensor"), "AB:CD:EF:01:02:03", false),
            ("ab", Some("Abbey Road"), "11:22:33:44:55:66", true),
            ("  ", Some("QT-Sensor"), "AA:BB:CC:00:11:22", false),
            ("no", None, "11:22:33:44:55:66", false),
        ];
        for &(query, name, addr, expected) in vectors {
            assert_eq!(
                matches(query, name, addr),
                expected,
                "{query:?} {name:?} {addr}"
            );
        }
    }

    #[test]
    fn query_is_trimmed_and_unicode_lowercased() {
        assert!(matches(
            "  qt\u{feff}",
            Some("QT-Sensor"),
            "AA:BB:CC:00:11:22"
        ));
        assert!(matches("ÄPFEL", Some("äpfel 1"), "AA:BB:CC:00:11:22"));
        assert!(!matches("", Some("x"), "AA:BB:CC:00:11:22"));
        // U+0085 is White_Space to Rust but not trimmed by JS, so it stays part of the query.
        assert!(!matches("\u{85}qt", Some("QT-Sensor"), "AA:BB:CC:00:11:22"));
        assert!(!matches(" \u{a0}\u{3000}", Some(" "), "AA:BB:CC:00:11:22"));
    }
}
