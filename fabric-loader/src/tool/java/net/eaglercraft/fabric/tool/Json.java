package net.eaglercraft.fabric.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null) so the tool has no extra dependency. */
final class Json {
	private final String text;
	private int pos;

	private Json(final String text) {
		this.text = text;
	}

	/** Parses a document into Map / List / String / Double / Boolean / null. */
	static Object parse(final String text) {
		Json parser = new Json(text);
		parser.skipWhitespace();
		Object value = parser.readValue();
		parser.skipWhitespace();
		if (parser.pos != text.length()) {
			throw parser.error("Trailing content");
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> asObject(final Object value) {
		return value instanceof Map ? (Map<String, Object>) value : null;
	}

	@SuppressWarnings("unchecked")
	static List<Object> asArray(final Object value) {
		return value instanceof List ? (List<Object>) value : null;
	}

	static String asString(final Object value) {
		return value instanceof String s ? s : null;
	}

	private Object readValue() {
		if (this.pos >= this.text.length()) {
			throw error("Unexpected end of input");
		}
		char c = this.text.charAt(this.pos);
		switch (c) {
			case '{':
				return readObject();
			case '[':
				return readArray();
			case '"':
				return readString();
			case 't':
				expectWord("true");
				return Boolean.TRUE;
			case 'f':
				expectWord("false");
				return Boolean.FALSE;
			case 'n':
				expectWord("null");
				return null;
			default:
				return readNumber();
		}
	}

	private Map<String, Object> readObject() {
		Map<String, Object> result = new LinkedHashMap<>();
		this.pos++;
		skipWhitespace();
		if (peek() == '}') {
			this.pos++;
			return result;
		}
		while (true) {
			skipWhitespace();
			if (peek() != '"') {
				throw error("Expected object key");
			}
			String key = readString();
			skipWhitespace();
			if (peek() != ':') {
				throw error("Expected ':'");
			}
			this.pos++;
			skipWhitespace();
			result.put(key, readValue());
			skipWhitespace();
			char c = peek();
			this.pos++;
			if (c == '}') {
				return result;
			}
			if (c != ',') {
				throw error("Expected ',' or '}'");
			}
		}
	}

	private List<Object> readArray() {
		List<Object> result = new ArrayList<>();
		this.pos++;
		skipWhitespace();
		if (peek() == ']') {
			this.pos++;
			return result;
		}
		while (true) {
			skipWhitespace();
			result.add(readValue());
			skipWhitespace();
			char c = peek();
			this.pos++;
			if (c == ']') {
				return result;
			}
			if (c != ',') {
				throw error("Expected ',' or ']'");
			}
		}
	}

	private String readString() {
		StringBuilder out = new StringBuilder();
		this.pos++;
		while (true) {
			if (this.pos >= this.text.length()) {
				throw error("Unterminated string");
			}
			char c = this.text.charAt(this.pos++);
			if (c == '"') {
				return out.toString();
			}
			if (c != '\\') {
				out.append(c);
				continue;
			}
			char e = this.text.charAt(this.pos++);
			switch (e) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'r' -> out.append('\r');
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'u' -> {
					out.append((char) Integer.parseInt(this.text.substring(this.pos, this.pos + 4), 16));
					this.pos += 4;
				}
				default -> out.append(e);
			}
		}
	}

	private Object readNumber() {
		int start = this.pos;
		while (this.pos < this.text.length() && "+-0123456789.eE".indexOf(this.text.charAt(this.pos)) >= 0) {
			this.pos++;
		}
		if (start == this.pos) {
			throw error("Unexpected character '" + this.text.charAt(this.pos) + "'");
		}
		return Double.valueOf(this.text.substring(start, this.pos));
	}

	private void expectWord(final String word) {
		if (!this.text.startsWith(word, this.pos)) {
			throw error("Expected " + word);
		}
		this.pos += word.length();
	}

	private char peek() {
		if (this.pos >= this.text.length()) {
			throw error("Unexpected end of input");
		}
		return this.text.charAt(this.pos);
	}

	private void skipWhitespace() {
		while (this.pos < this.text.length() && Character.isWhitespace(this.text.charAt(this.pos))) {
			this.pos++;
		}
	}

	private IllegalArgumentException error(final String message) {
		return new IllegalArgumentException(message + " at offset " + this.pos);
	}
}
