package com.boatfly;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BoatFlyClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("BoatFly");
    public static final String MOD_ID = "boatfly";

    private static KeyMapping boatFlightKey;
    private static KeyMapping increaseSpeedKey;
    private static KeyMapping decreaseSpeedKey;

    private static boolean isBoatFlyEnabled = false;
    private static double boatSpeedMultiplier = 1.0;
    private static double currentBoatVelocity = 8.0;

    private static final double DEFAULT_SPEED = 8.0;
    private static final double SPEED_INCREMENT = 1.0;
    private static final double SPEED_DECREMENT = -1.0;
    private static final double JUMP_VELOCITY = 0.3;
    private static final double MIN_SPEED = 0.0;

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "main")
    );

    @Override
    public void onInitializeClient() {
        // 註冊按鍵綁定
        boatFlightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + MOD_ID + ".toggle_fly",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KEY_CATEGORY
        ));
        increaseSpeedKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + MOD_ID + ".increase_speed",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KEY_CATEGORY
        ));
        decreaseSpeedKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + MOD_ID + ".decrease_speed",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KEY_CATEGORY
        ));

        // 註冊用戶 Tick 事件監聽
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        // 註冊用戶端指令 (使用 Fabric API v2)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> command = ClientCommands.literal("boatspeed")
                    .then(ClientCommands.argument("speed", FloatArgumentType.floatArg((float) MIN_SPEED))
                            .executes(context -> {
                                float speedValue = FloatArgumentType.getFloat(context, "speed");
                                changeSpeed(speedValue);
                                // 發送反饋訊息給玩家
                                context.getSource().sendFeedback(Component.translatable("message." + MOD_ID + ".speed_changed", String.format("%.2f", currentBoatVelocity)));
                                return 1;
                            }));
            dispatcher.register(command);
        });
    }

    private void onClientTick(Minecraft client) {
        // 確保玩家實例存在，否則不執行
        if (client.player == null) return;

        // 檢測開關按鍵是否被按下
        while (boatFlightKey.consumeClick()) {
            isBoatFlyEnabled = !isBoatFlyEnabled;
            if (isBoatFlyEnabled) {
                changeSpeed(DEFAULT_SPEED);
                client.player.sendSystemMessage(Component.translatable("message." + MOD_ID + ".fly_enabled", String.format("%.2f", currentBoatVelocity)));
            } else {
                client.player.sendSystemMessage(Component.translatable("message." + MOD_ID + ".fly_disabled"));
            }
        }

        // 若功能未啟用，直接返回
        if (!isBoatFlyEnabled) return;

        // 處理增加速度按鍵
        while (increaseSpeedKey.consumeClick()) {
            changeSpeed(currentBoatVelocity + SPEED_INCREMENT);
            client.player.sendSystemMessage(Component.translatable("message." + MOD_ID + ".speed_changed", String.format("%.2f", currentBoatVelocity)));
        }

        // 處理降低速度按鍵
        while (decreaseSpeedKey.consumeClick()) {
            if (currentBoatVelocity > MIN_SPEED) {
                changeSpeed(currentBoatVelocity + SPEED_DECREMENT);
                client.player.sendSystemMessage(Component.translatable("message." + MOD_ID + ".speed_changed", String.format("%.2f", currentBoatVelocity)));
            }
        }

        if (client.player.getVehicle() == null) return;
        Entity vehicle = client.player.getVehicle();
        if (vehicle == null) return;

        // 處理跳躍鍵 (控制垂直向上速度)
        if (client.options.keyJump.isDown()) {
            Vec3 velocity = vehicle.getDeltaMovement();
            vehicle.setDeltaMovement(velocity.x, JUMP_VELOCITY, velocity.z);
        }

        // 處理前進鍵 (應用速度倍率)
        if (client.options.keyUp.isDown() && boatSpeedMultiplier != 1.0D) {
            Vec3 velocity = vehicle.getDeltaMovement();
            Vec3 newVelocity = new Vec3(velocity.x * boatSpeedMultiplier, velocity.y, velocity.z * boatSpeedMultiplier);
            vehicle.setDeltaMovement(newVelocity);
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