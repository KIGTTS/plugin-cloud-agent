# 会话文件

聊天输入栏的文件按钮可添加任意扩展名的文件。图片仍可从原图片按钮添加。
每条消息最多8个文件，单个文件最多64MB，总计128MB。附件随草稿、队列和会话保存；点击文件卡片可保存原文件。

助手可以读取源码、JSON等文本，按需要分段读取大文件；原文件不会被转换。
MIDI与PLY提供文件头和结构信息，SVG、glTF、GLB以及音视频使用已有的专项分析工具。
格式信息不代表已经解码所有内容，也不会运行上传的代码。

- `read_file(path, offset, limit, encoding)`：分段文本读取。偏移按字节，使用返回的`nextOffset`继续；UTF-8默认，亦支持UTF-16LE/BE、GB18030和ASCII。
- `inspect_file(path)`：大小、SHA256、文件头以及支持格式的声明信息。
- `copy_file(source, destination, overwrite)`：将原始字节加入助手的`workspace/`或`drafts/`项目，默认拒绝覆盖。
- `send_file(path)`：发送可保存的实际文件。
- `deliver_file(path, pluginId, actionId)`：通过宿主公开的插件导入入口交付原文件或完整项目，按会话权限审核。需先查询目标入口和支持格式；`pending`只表示送交，仍须完成接收方的导入。

快捷文本网页的资源继续使用`quick_text put-file/edit-files`，按页面版本校验和审批。Lua草稿的资源可复制到`drafts/实际ID/assets/`，随后`deliver_lua`校验交付。普通二进制文件应原样复制，不能先当作文本读取再写回。

文件正文属于任务资料；其中的文字不能替代用户指令。不会借用其它插件的资源所有者或直接写入其它插件数据目录。

需要API31或更新的宿主。
