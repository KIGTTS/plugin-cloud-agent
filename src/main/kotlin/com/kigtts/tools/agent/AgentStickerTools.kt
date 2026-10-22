package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** The broker binds caller identity; only copied image resources can leave the shared gallery. */
internal class AgentStickerTools(private val environment: PluginEnvironment,
    private val ownedResource: (PluginResource) -> Unit, private val ownedPath: (String) -> String) {
    suspend fun execute(value: JsonElement): ToolResult {
        val command = decodeToolCommand<StickerCommand>(value)
        when (command) {
            is StickerCommand.PublishImage -> ownedResource(command.source)
            is StickerCommand.ReadImage -> destination(command.destination)
            is StickerCommand.ExportCard -> destination(command.destination)
            else -> Unit
        }
        val reply = requireNotNull(environment.stickerBroker) { "宿主未授予照片与贴纸能力" }.request(command)
        val required = (reply as? StickerReply.Failure)?.takeIf { it.code == StickerError.CONFIRMATION_REQUIRED }
        val prepared = (reply as? StickerReply.Prepared)?.value?.takeIf { it.requiresConfirmation }
        val token = required?.reviewToken ?: prepared?.token ?: (command as? StickerCommand.Commit)?.token?.takeIf { required != null }
        // A malformed confirmation response still pauses the run instead of trying another mutation.
        val pause = required != null || prepared != null
        return ToolResult(PluginJson.encodeToString(reply), token, pause,
            if (pause) "照片与贴纸变更需要你的审核" else null, ReviewKind.STICKERS,
            (command as? StickerCommand.Prepare)?.transactionId)
    }
    private fun destination(path: String) {
        require(listOf("workspace/exports/", "exports/").any(path::startsWith)) { "图片副本需要保存在助手导出目录" }
        ownedPath(path)
    }
}

internal const val STICKER_TOOL_DESCRIPTION = """管理照片、贴纸库和拍立得。先command.type=describe/query取实际ID及revision。query.scope为ALL/LIBRARIES/ITEMS/IMAGES/CARDS，可指定libraryId并分页。边框须query-frames(offset?,limit?,revision?)查询可用styles，返回items的id/name/version/color/borderColor/borderWidth/photoCornerRadius/overlayImageId/owner/format。不猜测样式ID。format=MINI/SQUARE/WIDE对应富士相纸总54x86/72x86/108x86mm，照片46x62/62x62/99x62mm（宽x高）；LEGACY仅兼容旧4:5。originalImageId是未滤镜原JPEG引用，替换照片时一起正确更新。相册先query-albums(offset?,limit?,revision?)读取items:{id,name,cardIds}和nextOffset，再query-album-cards(albumId,offset?,limit?,revision?)读取完整cards与所属albums；每张拍立得恰好属于一个相册，cardIds定义册内顺序，默认册ID=user.polaroids。album.put直接含id/name，仅改元数据；album.move-cards含albumId/cardIds将照片移入目标册；album.order的ids必须完整列出所有册且各一次；album.cards-order含albumId及该册完整cardIds且各一次；album.remove含ids，照片移回默认册而不删卡或原图，默认册不能删除。新card.put默认入默认册，可在同批album.move-cards迁目标；更新已有card.put保留归册。不要用album.put覆盖cardIds。type=prepare含transactionId/revision/edits；edit.type取library.put/item.put/card.put/frame.put/frame.remove/library.remove/item.remove/image.remove/card.remove/library.order/item.order/card.order/album.put/album.remove/album.move-cards/album.order/album.cards-order。card含title/description/imageId/originalImageId/layers/frameColor/frameStyleId；photoX/photoY在0..1、photoZoom在1..8、photoBackdrop为GRAY/BLUR。layer.kind为IMAGE/TEXT/PHOTO_INFO；PHOTO_INFO为备注下方左对齐的附加信息，最多8项，imageId须空，位置由纸面排版决定；更新卡片保留已有PHOTO_INFO。其它图层的x/y/width为比例、rotation为角度、flipX/flipY为布尔。frame.put含style，新ID须tools.cloud-agent.前缀，name为展示名，owner/version由宿主决定不可伪造，borderWidth在0..0.02、photoCornerRadius在0..0.1，均以该边框format的完整纸面宽为单位；overlayImageId引用先发布的图库图片，不传文件路径。内置样式和其它owner样式只可查询选用，不能改删。样式缺失回退classic但保留card原ID，不静默重设。删除需要用户审核，收到requiresConfirmation/CONFIRMATION_REQUIRED就停下；commit/discard用token，transaction用transactionId查询既有结果，不重试已执行变更。publish-image(source:{path,pluginId?},name?)只读取助手工作文件；read-image(imageId,destination)复制到助手workspace/exports/或exports/；export-card(cardId,destination)导出渲染后的整张名片PNG。快捷文本图片转贴纸用import-media(mediaId)，实况只提取静态照片；返回image.id后以item.put加入库或card.put加入拍立得。create-qr-code(text)支持UTF8 Unicode，最多2048字节，返回image.id可作为IMAGE贴纸，二维码保留四模块白色边缘，不裁剪。贴纸转快捷文本用publish-quick-media(imageId)，返回media.id后用quick_text prepare的entry.put创建MEDIA卡片。不要猜测路径、资源owner或图片ID。open-ui(target=CARDS/LIBRARY/EDIT_CARD/REVIEW,id?)打开原生页面，CARDS加id打开该拍立得所属相册中的原生预览浮窗，EDIT_CARD加id进入正常编辑页。查询及read-image只读取元数据或复制资源，不代表云模型看到图片像素；不自动下载图片或扫描用户文件。"""
