package com.huhutalk;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.GeckoEntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager;
import com.huhutalk.client.render.PhoneInHandLayer;
import com.huhutalk.core.chat.MaidPhoneContexts;
import com.huhutalk.core.chat.SendEmoteTool;
import com.huhutalk.core.move.MoveToTool;
import com.huhutalk.core.move.TaskMoveTo;
import com.huhutalk.core.supply.TaskRemoteSupply;
import com.huhutalk.core.transfer.SendItemTool;
import com.huhutalk.init.HuhuTalkItems;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 车万女仆(TLM)扩展入口。
 * <p>
 * 通过 {@link LittleMaidExtension} 注解被 TLM 扫描发现（见 AnnotatedInstanceUtil），
 * 需要提供无参构造。这里把本模组的手机物品绑定为女仆配饰，
 * 使它们能被放入女仆配饰栏，并被 {@code BaubleItemHandler.containsItem} 检测到。
 */
@LittleMaidExtension
public class HuhuTalkTLMExtension implements ILittleMaid {

    @Override
    public void bindMaidBauble(BaubleManager manager) {
        // 手机物品本体即 IMaidBauble 实现，直接绑定
        manager.bind(HuhuTalkItems.PHONE_WATCH.get(), HuhuTalkItems.PHONE_WATCH.get());
        manager.bind(HuhuTalkItems.PHONE_SMART.get(), HuhuTalkItems.PHONE_SMART.get());
    }

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(TaskMoveTo.INSTANCE);
        // 远程补给：主人血量/饱食度低时自动从她背包里出东西（需求 2.1，图标金苹果）
        manager.add(TaskRemoteSupply.INSTANCE);
    }

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new MoveToTool());
        register.register(new SendEmoteTool());
        // 传文件（M→P）：女仆把自己身上/隙间里的物品发给主人，主人聊天里点气泡领取
        register.register(new SendItemTool());
    }

    /**
     * 女仆的「手机 / 远程支援」常识：注册进 TLM 的上下文知识库。
     * <p>她因此<b>本来就知道</b>自己能远程支援主人，不需要在救援提示词里每次重讲一遍
     * （重讲会把她的注意力拉到"让主人看消息"上）。详见 {@link MaidPhoneContexts}。
     */
    @Override
    public void registerAIMaidContext(GameContextRegister register) {
        MaidPhoneContexts.registerAll(register);
    }

    /**
     * 给女仆的 Gecko 渲染器加一个「手机挂手上」的渲染层（仅在手机来源窗口内显示）。
     * <p>
     * 这是 TLM 的官方扩展点（{@code ILittleMaid#addAdditionGeckoMaidLayer}），
     * 由 {@code GeckoEntityMaidRenderer} 构造时回调，内部走公开的
     * {@code addGeoLayerRenderer} → {@code addLayer}。挂点用模型自带的
     * {@code RightHandLocator} 骨骼，所以**不需要改动任何模型文件**。
     * <p>
     * {@code @OnlyIn(Dist.CLIENT)}：方法签名引用了客户端类，必须标注，
     * 否则专用服务端加载本类时会因 RuntimeDistCleaner 之外的原因解析失败。
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void addAdditionGeckoMaidLayer(GeckoEntityMaidRenderer<? extends Mob> renderer,
                                          EntityRendererProvider.Context context) {
        renderer.addGeoLayerRenderer(new PhoneInHandLayer<>(renderer, context.getItemInHandRenderer()));
    }
}
