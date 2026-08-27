package com.totalecollapse.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.totalecollapse.StackItems;

import net.minecraft.world.item.ItemStack;

/**
 * The single funnel every merge, shift-click, hopper transfer and pickup goes
 * through. Injecting at RETURN rather than HEAD means the vanilla answer is
 * available, so the policy can key off it and mods that set the max stack size
 * component still get honoured as the baseline.
 */
@Mixin(ItemStack.class)
public class ItemStackMaxCountMixin {

    @Inject(method = "getMaxStackSize", at = @At("RETURN"), cancellable = true)
    private void totalecollapse$raiseStackLimit(CallbackInfoReturnable<Integer> callback) {
        int vanilla = callback.getReturnValueI();
        int raised = StackItems.limitFor((ItemStack) (Object) this, vanilla);

        if (raised != vanilla) {
            callback.setReturnValue(raised);
        }
    }
}
