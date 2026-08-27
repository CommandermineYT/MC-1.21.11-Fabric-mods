package com.totalecollapse.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.totalecollapse.StackItems;

import net.minecraft.world.Container;

/**
 * This is the mod-compatibility piece. Container#getMaxStackSize is an interface
 * default method, so every chest, barrel, hopper, furnace and modded storage
 * block that does not override it inherits this one implementation. Patching the
 * interface therefore covers containers from mods this file has never heard of,
 * including wearable-inventory mods such as Adventurer's Backpack, with no
 * per-mod code. Mods that do override it keep their own behaviour.
 */
@Mixin(Container.class)
public interface ContainerMaxCountMixin {

    @Inject(method = "getMaxStackSize()I", at = @At("RETURN"), cancellable = true)
    private void totalecollapse$raiseContainerLimit(CallbackInfoReturnable<Integer> callback) {
        int vanilla = callback.getReturnValueI();
        int raised = StackItems.slotLimit(vanilla);

        if (raised != vanilla) {
            callback.setReturnValue(raised);
        }
    }
}
