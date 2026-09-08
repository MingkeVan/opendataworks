const SECRET_PATTERNS = [
  /password\s*=\s*[^\s,;]+/gi,
  /token\s*=\s*[^\s,;]+/gi,
  /api[_-]?key\s*[:=]\s*[^\s,;]+/gi,
  /sk-[a-zA-Z0-9_-]{20,}/g,
  /bearer\s+[a-zA-Z0-9_.-]{20,}/gi,
];

export class Redactor {
  public static redactString(text: string): string {
    if (!text) return text;
    let result = text;
    for (const pattern of SECRET_PATTERNS) {
      result = result.replace(pattern, (match) => {
        const parts = match.split(/[:=]/);
        if (parts.length === 2) {
          return `${parts[0]}=***REDACTED***`;
        }
        return "***REDACTED***";
      });
    }
    return result;
  }

  public static redactObject<T>(obj: T): T {
    if (obj === null || typeof obj !== "object") {
      if (typeof obj === "string") {
        return this.redactString(obj) as unknown as T;
      }
      return obj;
    }

    if (Array.isArray(obj)) {
      return obj.map((item) => this.redactObject(item)) as unknown as T;
    }

    const result: Record<string, any> = {};
    for (const [key, value] of Object.entries(obj)) {
      const lowerKey = key.toLowerCase();
      if (
        lowerKey.includes("password") ||
        lowerKey.includes("secret") ||
        lowerKey.includes("token") ||
        lowerKey.includes("apikey") ||
        lowerKey.includes("api_key")
      ) {
        result[key] = "***REDACTED***";
      } else {
        result[key] = this.redactObject(value);
      }
    }
    return result as T;
  }
}
