package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;

public class FreeLook {
    static FreeLook INSTANCE = null;
    float cameraYaw = 0;
    float cameraPitch = 0;
    Perspective prevPerspective;
    private Perspective ownedPerspective;
    boolean enabled = false;
    int ticksSinceLastRotation = 0;

//    BooleanSetting changePers = addBooleanSetting("Change Perspective",true);
//    EnumSetting<Mode> cameraMode = addEnumSetting("Camera Mode",Mode.CAMERA);
//    FloatSetting sensitivity = addFloatSetting("Sensitivity",8,0,10);
    private FreeLook() {
        PrinterConfig.FREE_LOOK.setValueChangeCallback(this::setEnabled);
    }

    /** Native packet rotations leave normal mouse and camera controls in charge. */
    public static boolean nativeMode() {
        return PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue()
                || PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
    }

    public static FreeLook getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FreeLook();
        }
        return INSTANCE;
    }

    public void onGameTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (nativeMode() || !PrinterConfig.FREE_LOOK.getBooleanValue()
                || mc == null || mc.player == null || mc.options == null) {
            onDisable();
            return;
        }
        if (!enabled) onEnable();
        // F5 belongs to the user. Once changed, do not later restore an old
        // perspective as if FreeLook still owned the current choice.
        if (ownedPerspective != null && mc.options.getPerspective() != ownedPerspective) ownedPerspective = null;
        if (shouldRotate() && ((ticksSinceLastRotation -1) == PrinterConfig.FREE_LOOK_LOOK_BACK.getIntegerValue() || PrinterConfig.FREE_LOOK_LOOK_BACK_ALWAYS_ROTATE_PLAYER.getBooleanValue())) {
            if (mc.player != null) {
                // Reset player rotation. The mouse mixin only rotates the player by a delta. So without this the player
                // rotation would be offset from the camera rotation.
                mc.player.setYaw(cameraYaw);
                mc.player.setPitch(cameraPitch);
            }
        }
        ticksSinceLastRotation++;
    }

    private void setEnabled(ConfigBoolean configBoolean) {
        if (configBoolean.getBooleanValue()) {
            onEnable();
        } else {
            onDisable();
        }
    }

    void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (nativeMode()) {
            onDisable();
            return;
        }
        if (enabled || mc == null || mc.player == null || mc.options == null) {
            return;
        }
        this.enabled = true;
        if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) System.out.println("FreeLook: onEnable");

        cameraPitch = mc.player.getPitch();
        cameraYaw = mc.player.getYaw();
        ticksSinceLastRotation = 0;
        prevPerspective = mc.options.getPerspective();
        Perspective requestedPerspective = PrinterConfig.FREE_LOOK_THIRD_PERSON.getBooleanValue()
                ? Perspective.THIRD_PERSON_BACK : Perspective.FIRST_PERSON;
        if (requestedPerspective != prevPerspective) {
            ownedPerspective = requestedPerspective;
            mc.options.setPerspective(requestedPerspective);
        }
    }

    void onDisable() {
        boolean wasEnabled = this.enabled;
        this.enabled = false;
        if (wasEnabled && PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) System.out.println("FreeLook: onDisable");
        MinecraftClient mc = MinecraftClient.getInstance();
        if (ownedPerspective != null && prevPerspective != null && mc != null && mc.options != null
                && mc.options.getPerspective() == ownedPerspective) mc.options.setPerspective(prevPerspective);
        ownedPerspective = null;
    }

    public boolean isEnabled() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return enabled && !nativeMode() && PrinterConfig.FREE_LOOK.getBooleanValue()
                && mc != null && mc.player != null;
    }

    public boolean shouldRotate() {
        return isEnabled() && PrinterConfig.FREE_LOOK_LOOK_BACK.getIntegerValue() != 0 && ticksSinceLastRotation > PrinterConfig.FREE_LOOK_LOOK_BACK.getIntegerValue();
    }

    public float getCameraYaw() {
        return cameraYaw;
    }

    public float getCameraPitch() {
        return cameraPitch;
    }

    public void setCameraYaw(float v) {
        cameraYaw = v;
    }

    public void setCameraPitch(float v) {
        cameraPitch = v;
    }

    public void setPrevPerspective(Perspective perspective) {
        prevPerspective = perspective;
    }

    public Perspective getPrevPerspective() {
        return prevPerspective;
    }
}
