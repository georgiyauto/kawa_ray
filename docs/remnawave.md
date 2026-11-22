# Server descriptions with Remnawave

Remnawave adds each host's *server description* (`meta.serverDescription` in
JSON, `?serverDescription=` in links) only for "extended clients". It
recognises Happ, INCY, FlClash X and a few others by User-Agent; KAWARAY has to
be added by the panel admin.

In the panel: **Subscription settings → Response rules**, add this rule
**before** the final fallback rule:

```json
{
  "name": "KAWARAY",
  "description": "KAWARAY desktop client: xray JSON with server descriptions",
  "enabled": true,
  "operator": "AND",
  "conditions": [
    { "headerName": "user-agent", "operator": "REGEX", "value": "^Kawaray/", "caseSensitive": false }
  ],
  "responseType": "XRAY_JSON",
  "responseModifications": {
    "additionalExtendedClientsRegex": ["^Kawaray/"]
  }
}
```

KAWARAY requests the subscription URL as given first (response rules apply
there) and only falls back to `<url>/json`, which ignores the rules.
