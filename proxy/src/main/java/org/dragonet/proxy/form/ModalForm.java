package org.dragonet.proxy.form;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.log4j.Log4j2;
import org.dragonet.proxy.form.components.ButtonComponent;

import java.util.ArrayList;
import java.util.List;

@Log4j2
public class ModalForm extends Form {

    private String content = "";

    private final List<ButtonComponent> components = new ArrayList<>();

    public ModalForm(String title, String content) {
        super("form", title);
        this.content = content;
    }

    public ModalForm addComponent(ButtonComponent component) {
        components.add(component);
        return this;
    }

    @Override
    public JsonObject serialize() {
        JsonArray buttons = new JsonArray();
        components.forEach((button) -> {
            buttons.add(button.serialize());
        });

        JsonObject object = super.serialize();
        object.addProperty("content", content);
        object.add("buttons", buttons);
        return object;
    }
}
