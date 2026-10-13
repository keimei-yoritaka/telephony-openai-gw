package com.example.telephonygw.openai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

final class JsonCodec {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();
    private JsonCodec() { }
    static String encode(Object value) { return JSON.toJson(value); }
    static JsonObject decode(String value) { return JSON.fromJson(value, JsonObject.class); }
    static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
