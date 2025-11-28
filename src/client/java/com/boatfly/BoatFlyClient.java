package com.boatfly;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BoatFlyClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("BoatFly");
    public static final String MOD_ID = "boatfly";

    private static KeyBinding boatFlightKey;
    private static KeyBinding increaseSpeedKey;
    private static KeyBinding decreaseSpeedKey;

    private static boolean isBoatFlyEnabled = false;
    private static double boatSpeedMultiplier = 1.0;
    private static double currentBoatVelocity = 8.0;

    private static final double DEFAULT_SPEED = 8.0;
    private static final double SPEED_INCREMENT = 1.0;
    private static final double SPEED_DECREMENT = -1.0;
    private static final double JUMP_VELOCITY = 0.3;
    private static final double MIN_SPEED = 0.0;

    private static final KeyBinding.Category KEY_CATEGORY = new KeyBinding.Category(Identifier.of(MOD_ID, "main"));

    @Override
    public void onInitializeClient() {
        // 註冊按鍵綁定
        boatFlightKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + MOD_ID + ".toggle_fly",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KEY_CATEGORY
        ));
        increaseSpeedKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + MOD_ID + ".increase_speed",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KEY_CATEGORY
        ));
        decreaseSpeedKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + MOD_ID + ".decrease_speed",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KEY_CATEGORY
        ));

        // 註冊用戶端 Tick 事件監聽器
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        // 註冊用戶端指令 (使用 Fabric API v2)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> command = ClientCommandManager.literal("boatspeed")
                    .then(ClientCommandManager.argument("speed", FloatArgumentType.floatArg((float) MIN_SPEED))
                            .executes(context -> {
                                float speedValue = FloatArgumentType.getFloat(context, "speed");
                                changeSpeed(speedValue);
                                // 發送反饋訊息給玩家
                                context.getSource().sendFeedback(Text.translatable("message." + MOD_ID + ".speed_changed", String.format("%.2f", currentBoatVelocity)));
                                return 1;
                            }));
            dispatcher.register(command);
        });
    }

    private void onClientTick(MinecraftClient client) {
        // 確保玩家實例存在，否則不執行
        if (client.player == null) return;

        // 檢測開關按鍵是否被按下
        if (boatFlightKey.wasPressed()) {
            isBoatFlyEnabled = !isBoatFlyEnabled;
            if (isBoatFlyEnabled) {
                changeSpeed(DEFAULT_SPEED);
                client.player.sendMessage(Text.translatable("message." + MOD_ID + ".fly_enabled", String.format("%.2f", currentBoatVelocity)), false);
            } else {
                client.player.sendMessage(Text.translatable("message." + MOD_ID + ".fly_disabled"), false);
            }
        }

        // 若功能未啟用，直接返回
        if (!isBoatFlyEnabled) return;

        // 處理增加速度按鍵
        if (increaseSpeedKey.wasPressed()) {
            changeSpeed(currentBoatVelocity + SPEED_INCREMENT);
            client.player.sendMessage(Text.translatable("message." + MOD_ID + ".speed_changed", String.format("%.2f", currentBoatVelocity)), false);
        }

        // 處理降低速度按鍵
        if (decreaseSpeedKey.wasPressed()) {
            if (currentBoatVelocity > MIN_SPEED) {
                changeSpeed(currentBoatVelocity + SPEED_DECREMENT);
                client.player.sendMessage(Text.translatable("message." + MOD_ID + ".speed_changed", String.format("%.2f", currentBoatVelocity)), false);
            }
        }

        if (!client.player.hasVehicle()) return;
        Entity vehicle = client.player.getVehicle();
        if (vehicle == null) return;

        // 處理跳躍鍵 (控制垂直向上速度)
        if (client.options.jumpKey.isPressed()) {
            Vec3d velocity = vehicle.getVelocity();
            vehicle.setVelocity(velocity.x, JUMP_VELOCITY, velocity.z);
        }

        // 處理前進鍵 (應用速度倍率)
        if (client.options.forwardKey.isPressed() && boatSpeedMultiplier != 1.0) {
            Vec3d velocity = vehicle.getVelocity();
            Vec3d newVelocity = new Vec3d(velocity.x * boatSpeedMultiplier, velocity.y, velocity.z * boatSpeedMultiplier);
            vehicle.setVelocity(newVelocity);
        }
    }

    private void changeSpeed(double newSpeed) {
        currentBoatVelocity = Math.max(MIN_SPEED, newSpeed);
        boatSpeedMultiplier = calculateMultiplier(currentBoatVelocity);
        LOGGER.info("Boat speed set to {} b/s, multiplier is {}.", String.format("%.2f", currentBoatVelocity), String.format("%.5f", boatSpeedMultiplier));
    }

    private double calculateMultiplier(double velocity) {
        // f(v) = (-5.33893 * (ln(v - 8 + 11.9072))^(-3.31832) + 1.26253)^(0.470998)
        if (velocity <= 0) return 0;

        double logInput = velocity - 8.0 + 11.9072;
        if (logInput <= 0) return 1.0;

        double term1 = -5.33893 * Math.pow(Math.log(logInput), -3.31832);
        double base = term1 + 1.26253;
        if (base < 0) return 1.0;

        return Math.pow(base, 0.470998);
    }
}