package dev.frozenclient.fabricbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader: objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Double. */
final class Json {
	private final String s;
	private int i;

	private Json(final String s) {
		this.s = s;
	}

	static Object parse(final String text) {
		Json j = new Json(text);
		j.ws();
		Object v = j.value();
		j.ws();
		if (j.i != j.s.length()) {
			throw j.error("trailing data");
		}
		return v;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> obj(final Object o) {
		return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	static List<Object> arr(final Object o) {
		return o instanceof List ? (List<Object>) o : new ArrayList<>();
	}

	static String str(final Object o) {
		return o instanceof String ? (String) o : null;
	}

	private RuntimeException error(final String msg) {
		return new IllegalArgumentException("JSON " + msg + " at offset " + this.i);
	}

	private void ws() {
		while (this.i < this.s.length() && Character.isWhitespace(this.s.charAt(this.i))) {
			this.i++;
		}
	}

	private Object value() {
		if (this.i >= this.s.length()) {
			throw error("unexpected end");
		}
		char c = this.s.charAt(this.i);
		switch (c) {
			case '{': return object();
			case '[': return array();
			case '"': return string();
			case 't': expect("true"); return Boolean.TRUE;
			case 'f': expect("false"); return Boolean.FALSE;
			case 'n': expect("null"); return null;
			default: return number();
		}
	}

	private void expect(final String word) {
		if (!this.s.startsWith(word, this.i)) {
			throw error("expected " + word);
		}
		this.i += word.length();
	}

	private Map<String, Object> object() {
		Map<String, Object> map = new LinkedHashMap<>();
		this.i++;
		ws();
		if (peek() == '}') {
			this.i++;
			return map;
		}
		while (true) {
			ws();
			String key = string();
			ws();
			if (peek() != ':') {
				throw error("expected ':'");
			}
			this.i++;
			ws();
			map.put(key, value());
			ws();
			char c = peek();
			this.i++;
			if (c == '}') {
				return map;
			}
			if (c != ',') {
				throw error("expected ',' or '}'");
			}
		}
	}

	private List<Object> array() {
		List<Object> list = new ArrayList<>();
		this.i++;
		ws();
		if (peek() == ']') {
			this.i++;
			return list;
		}
		while (true) {
			ws();
			list.add(value());
			ws();
			char c = peek();
			this.i++;
			if (c == ']') {
				return list;
			}
			if (c != ',') {
				throw error("expected ',' or ']'");
			}
		}
	}

	private char peek() {
		if (this.i >= this.s.length()) {
			throw error("unexpected end");
		}
		return this.s.charAt(this.i);
	}

	private String string() {
		if (peek() != '"') {
			throw error("expected string");
		}
		this.i++;
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = peek();
			this.i++;
			if (c == '"') {
				return sb.toString();
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			char e = peek();
			this.i++;
			switch (e) {
				case 'n': sb.append('\n'); break;
				case 't': sb.append('\t'); break;
				case 'r': sb.append('\r'); break;
				case 'b': sb.append('\b'); break;
				case 'f': sb.append('\f'); break;
				case 'u':
					sb.append((char) Integer.parseInt(this.s.substring(this.i, this.i + 4), 16));
					this.i += 4;
					break;
				default: sb.append(e);
			}
		}
	}

	private Double number() {
		int start = this.i;
		while (this.i < this.s.length() && "+-0123456789.eE".indexOf(this.s.charAt(this.i)) >= 0) {
			this.i++;
		}
		if (start == this.i) {
			throw error("unexpected character '" + this.s.charAt(this.i) + "'");
		}
		return Double.valueOf(this.s.substring(start, this.i));
	}
}
