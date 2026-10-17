package dev.aigw.core.provider.marvis

import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView

/**
 * 静态模型目录（合同 §9）。
 *
 * TODO: 真实模型目录在 Node 桥（model.list，recon-aux §3），MVP 不实现拉取，
 * 这里只放一条占位展示项；请求里的 model 名不进入 chat.send，上游模型选择不受其影响。
 */
internal object MarvisModelCatalog {

    val MODELS: List<ProviderModel> = listOf(
        ProviderModel(id = "marvis-default", name = "Marvis 默认模型"),
    )

    fun view(): ProviderModelCatalogView = ProviderModelCatalogView(
        models = MODELS,
        fromFallback = false,
        error = "",
    )
}
