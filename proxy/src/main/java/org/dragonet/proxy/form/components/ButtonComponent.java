package org.dragonet.proxy.form.components;

import com.google.gson.JsonObject;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class ButtonComponent {

    private String text = "";
    private String type = "url";
    private String data = "";

    public ButtonComponent(String text) {
        this.text = text;
    }

    public ButtonComponent(String text, String data) {
        this.text = text;
        this.data = data;
    }

    public JsonObject serialize() {
        JsonObject object = new JsonObject();
        object.addProperty("text", text);

        if(!data.isEmpty() && !type.isEmpty()) {
            JsonObject image = new JsonObject();
            image.addProperty("type", type);
            image.addProperty("data", data);
            object.add("image", image);
        }

        return object;
    }

}
