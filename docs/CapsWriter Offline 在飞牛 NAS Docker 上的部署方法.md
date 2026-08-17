# CapsWriter Offline 在飞牛 NAS Docker 上的部署方法

## 一、部署目标

在飞牛 NAS 上运行 CapsWriter Offline 服务端：

- Docker 基础镜像：`lindozy/python3.11:latest`
- 语音模型：SenseVoice-Small ONNX
- 推理方式：CPU
- 服务端口：`6016`
- Windows 客户端通过 WebSocket 连接 NAS
- 容器支持自动重启和 NAS 开机自动恢复

不要使用飞牛商店提供的 Python，也不要直接使用普通的 `python:3.11` 镜像。

---

## 二、目录结构

在飞牛 NAS 上创建目录：

```text
/vol1/1000/docker/capswriter/
├── app/
│   ├── start_server.py
│   ├── config_server.py
│   ├── core/
│   ├── requirements-server.txt
│   └── 其他 CapsWriter 源码文件
├── models/
│   └── SenseVoice-Small/
│       └── Sensevoice-Small-ONNX/
│           ├── SenseVoice-Encoder.fp16.onnx
│           ├── SenseVoice-CTC.fp16.onnx
│           └── tokenizer.bpe.model
├── Dockerfile
└── docker-compose.yml
```

CapsWriter 源码放在：

```text
/vol1/1000/docker/capswriter/app/
```

模型放在：

```text
/vol1/1000/docker/capswriter/models/
```

注意模型目录大小写必须正确：

```text
SenseVoice-Small/Sensevoice-Small-ONNX/
```

其中 `Sensevoice` 的 `v` 是小写。

---

## 三、修改服务端模型配置

编辑：

```text
/vol1/1000/docker/capswriter/app/config_server.py
```

找到：

```python
model_type = 'qwen_asr'
```

改成：

```python
model_type = 'sensevoice'
```

也可以通过 SSH 执行：

```bash
cd /vol1/1000/docker/capswriter

sed -i "s/model_type = 'qwen_asr'/model_type = 'sensevoice'/" app/config_server.py
```

确认配置：

```bash
grep "model_type =" app/config_server.py
```

应显示：

```text
model_type = 'sensevoice'
```

服务地址和端口保持：

```python
addr = '0.0.0.0'
port = '6016'
```

---

## 四、Dockerfile

创建或修改：

```text
/vol1/1000/docker/capswriter/Dockerfile
```

内容如下：

```dockerfile
FROM lindozy/python3.11:latest

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        libsndfile1 \
        portaudio19-dev \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
    sherpa-onnx \
    numpy \
    gguf \
    rich \
    websockets \
    watchdog \
    pypinyin \
    pystray \
    Pillow \
    markdown \
    tkhtmlview \
    colorama \
    psutil \
    pyperclip \
    onnxruntime \
    sentencepiece \
    soundfile

EXPOSE 6016

CMD ["python", "start_server.py"]
```

这些依赖分别用于：

- `sherpa-onnx`：语音识别引擎
- `onnxruntime`：运行 SenseVoice ONNX 模型
- `sentencepiece`：加载分词模型
- `soundfile`：读取音频
- `rich`、`websockets`、`watchdog` 等：CapsWriter 服务运行所需组件
- `ffmpeg`、`libsndfile1`、`portaudio19-dev`：系统音频依赖

不要安装：

```text
onnxruntime-directml
```

NAS 使用 CPU 推理即可。

---

## 五、docker-compose.yml

创建或修改：

```text
/vol1/1000/docker/capswriter/docker-compose.yml
```

内容如下：

```yaml
services:
  capswriter:
    build:
      context: .
      dockerfile: Dockerfile

    image: capswriter:latest

    container_name: capswriter

    restart: unless-stopped

    ports:
      - "6016:6016"

    volumes:
      - /vol1/1000/docker/capswriter/app:/app
      - /vol1/1000/docker/capswriter/models:/app/models

    working_dir: /app

    environment:
      TZ: Asia/Tokyo
```

说明：

- `/app` 映射 CapsWriter 源码
- `/app/models` 映射模型目录
- `restart: unless-stopped` 负责容器异常退出和 Docker 重启后的自动恢复
- Dockerfile 中已经设置了启动命令，因此 Compose 中不再需要额外写 `command`
- 不要使用 `sleep infinity` 或手动进入容器启动服务

---

## 六、构建镜像

通过 SSH 登录飞牛 NAS：

```bash
# 注意用你自己的飞牛用户名，不要复制我的用户名jinn
ssh jinn@192.168.1.3
```

进入部署目录：

```bash
cd /vol1/1000/docker/capswriter
```

执行构建：

```bash
HOME=/vol1/1000/docker/capswriter docker compose build --no-cache
```

这里必须临时设置：

```bash
HOME=/vol1/1000/docker/capswriter
```

原因是飞牛用户 `jinn` 的默认 HOME 可能是：

```text
/home/jinn
```

但该目录不存在或不可写，Docker Compose 构建时可能报错：

```text
mkdir /home/jinn: permission denied
```

设置 HOME 后，Compose 会使用可写目录保存构建过程所需的临时配置。

构建成功后应看到类似：

```text
✔ capswriter:latest Built
```

以后如果不想重复输入，可以在当前 SSH 会话执行：

```bash
export HOME=/vol1/1000/docker/capswriter
```

之后直接使用：

```bash
docker compose build
docker compose up -d
```

---

## 七、启动容器

构建完成后执行：

```bash
HOME=/vol1/1000/docker/capswriter docker compose up -d
```

检查容器：

```bash
docker ps -a --filter name=capswriter
```

正常状态应为：

```text
capswriter   Up ...
```

如果看到：

```text
Restarting (1)
```

说明服务启动报错，应立即查看日志。

---

## 八、检查模型路径

执行：

```bash
docker exec capswriter find /app/models/SenseVoice-Small -maxdepth 2 -type f
```

应看到：

```text
/app/models/SenseVoice-Small/Sensevoice-Small-ONNX/SenseVoice-Encoder.fp16.onnx
/app/models/SenseVoice-Small/Sensevoice-Small-ONNX/SenseVoice-CTC.fp16.onnx
/app/models/SenseVoice-Small/Sensevoice-Small-ONNX/tokenizer.bpe.model
```

如果文件直接放在：

```text
/app/models/SenseVoice-Small/
```

而没有 `Sensevoice-Small-ONNX` 这一层，服务可能无法找到模型。

---

## 九、查看服务日志

查看最近日志：

```bash
docker logs --tail 100 capswriter
```

持续查看日志：

```bash
docker logs -f capswriter
```

启动成功时应看到：

```text
CapsWriter Offline Server
绑定的服务地址：0.0.0.0:6016
模型文件检查通过 (sensevoice)
开始服务
```

退出日志查看：

```text
Ctrl+C
```

只会退出日志界面，不会停止容器。

---

## 十、Windows 客户端配置

在 Windows 客户端的配置文件中，将服务端地址设置为 NAS 地址：

```python
class ClientConfig:
    addr = '192.168.1.3'
    port = '6016'
```

不要填写：

```python
addr = '127.0.0.1'
```

因为 `127.0.0.1` 代表 Windows 本机，而服务实际运行在 NAS 上。

客户端连接地址为：

```text
ws://192.168.1.3:6016
```

测试 Windows 是否能访问端口：

```powershell
Test-NetConnection 192.168.1.3 -Port 6016
```

正常结果：

```text
TcpTestSucceeded : True
```

然后启动 Windows 客户端并进行语音测试。

---

## 十一、自测标准

服务端日志应出现：

```text
客户端已连接
```

语音识别时应出现类似：

```text
模型输出：喂喂，123。
片段拼接：喂喂，123。
格式化后：喂喂，123。
```

Windows 客户端应显示类似：

```text
已连接服务端: ws://192.168.1.3:6016
识别结果：喂喂，123
```

出现以上结果，就表示以下链路全部正常：

```text
Windows 客户端
    ↓
NAS:6016
    ↓
CapsWriter Docker
    ↓
SenseVoice ONNX
    ↓
CPU 识别
```

---

## 十二、开机自动启动

Compose 中必须保留：

```yaml
restart: unless-stopped
```

飞牛 Docker 界面的“开机自启”开关也建议打开。

验证容器重启：

```bash
docker restart capswriter
```

然后检查：

```bash
docker ps --filter name=capswriter
docker logs --tail 50 capswriter
```

如果重新出现：

```text
模型文件检查通过 (sensevoice)
开始服务
```

说明容器重启后能够自动恢复。

如需验证整个 NAS 重启后的自动启动：

```bash
sudo reboot
```

NAS 启动完成后执行：

```bash
docker ps --filter name=capswriter
```

确认容器为：

```text
Up ...
```

---

## 十三、故障排查

### 1. 出现 `mkdir /home/jinn: permission denied`

使用：

```bash
HOME=/vol1/1000/docker/capswriter docker compose build --no-cache
```

不要创建 `/home/jinn`，也不需要修改系统用户权限。

### 2. 出现 `ModuleNotFoundError`

不要进入容器临时安装依赖。应修改 Dockerfile 后重新构建：

```bash
HOME=/vol1/1000/docker/capswriter docker compose build --no-cache
HOME=/vol1/1000/docker/capswriter docker compose up -d
```

这样依赖会固化在 `capswriter:latest` 镜像中。

### 3. 出现 `No module named 'onnxruntime'`

确认 Dockerfile 中包含：

```text
onnxruntime
```

然后重新构建镜像。

### 4. 出现 `No module named 'soundfile'`

确认 Dockerfile 中包含：

```text
soundfile
```

并且系统依赖中包含：

```text
libsndfile1
```

然后重新构建。

### 5. 日志显示模型检查失败

检查目录：

```bash
docker exec capswriter find /app/models -maxdepth 5 -type f
```

确认三个文件都存在，并且路径为：

```text
/app/models/SenseVoice-Small/Sensevoice-Small-ONNX/
```

### 6. 容器一直重启

先查看日志：

```bash
docker logs --tail 200 capswriter
```

确认是否为 Python 依赖、模型路径或配置错误。

不要反复手动进入容器执行：

```bash
python start_server.py
```

正常情况下应由 Dockerfile 自动启动。

### 7. Windows 无法连接

依次检查：

```bash
docker ps --filter name=capswriter
docker logs --tail 100 capswriter
```

然后在 Windows 执行：

```powershell
Test-NetConnection 192.168.1.3 -Port 6016
```

确认：

- 容器状态为 `Up`
- 日志显示监听 `0.0.0.0:6016`
- Compose 端口映射为 `6016:6016`
- Windows 客户端地址为 `192.168.1.3`
- NAS 防火墙允许 TCP 6016 端口

---

## 十四、日常维护

查看状态：

```bash
docker ps --filter name=capswriter
```

查看日志：

```bash
docker logs --tail 100 capswriter
```

重启容器：

```bash
docker restart capswriter
```

修改 Dockerfile 或依赖后重新构建：

```bash
cd /vol1/1000/docker/capswriter

HOME=/vol1/1000/docker/capswriter docker compose build --no-cache
HOME=/vol1/1000/docker/capswriter docker compose up -d
```

模型位于 NAS 的独立目录：

```text
/vol1/1000/docker/capswriter/models/
```

因此重新构建镜像不会删除模型文件。

已补充 Windows 客户端配置和静默开机自启部分，路径使用泛指。

## 十五、Windows 客户端配置

假设 Windows 客户端目录为：

```text
<客户端目录>\
├── config_client.py
├── start_client.exe
└── 其他 CapsWriter 客户端文件
```

例如可以是：

```text
D:\Apps\CapsWriter-Offline\
```

请根据实际安装位置替换 `<客户端目录>`，不要照抄示例路径。

### 修改客户端服务端地址

用文本编辑器打开：

```text
<客户端目录>\config_client.py
```

找到：

```python
class ClientConfig:
    addr = '127.0.0.1'
    port = '6016'
```

改成 NAS 的实际地址：

```python
class ClientConfig:
    addr = '192.168.1.3'
    port = '6016'
```

端口保持：

```python
port = '6016'
```

保存文件。

注意：

- `192.168.1.3` 替换为 NAS 的实际 IP 地址
- 不要填写 `127.0.0.1`
- `config_client.py` 和 `start_client.exe` 应位于同一个客户端目录
- 如果程序已经打包并且不读取外部配置文件，应使用项目提供的配置方式重新生成客户端程序

### 手动测试客户端

双击：

```text
<客户端目录>\start_client.exe
```

或者在 PowerShell 中执行：

```powershell
Set-Location "<客户端目录>"
.\start_client.exe
```

启动后应连接：

```text
ws://192.168.1.3:6016
```

确认客户端能够正常识别语音后，再设置开机自启。

---

## 十六、Windows 客户端静默开机自启

推荐使用 Windows“任务计划程序”，不需要修改系统注册表，也不需要每次手动启动。

### 创建任务

1. 按 `Win + R`
2. 输入：

```text
taskschd.msc
```

3. 按回车打开“任务计划程序”
4. 点击右侧“创建任务”，不要选择“创建基本任务”

### 常规设置

在“常规”选项卡中：

- 名称填写：

```text
CapsWriter Client
```

- 选择“仅当用户登录时运行”
- 勾选“隐藏”
- 不要勾选“使用最高权限运行”，除非客户端确实需要管理员权限

“仅当用户登录时运行”可以保证客户端启动在当前桌面会话中，托盘图标和快捷键功能正常。

### 触发器设置

切换到“触发器”选项卡：

1. 点击“新建”
2. 开始任务选择：

```text
登录时
```

3. 选择当前 Windows 用户
4. 点击“确定”

### 操作设置

切换到“操作”选项卡：

1. 点击“新建”
2. 操作选择“启动程序”
3. “程序或脚本”填写：

```text
<客户端目录>\start_client.exe
```

4. “起始于”填写：

```text
<客户端目录>
```

例如：

```text
程序或脚本：
D:\Apps\CapsWriter-Offline\start_client.exe

起始于：
D:\Apps\CapsWriter-Offline
```

“起始于”必须填写客户端目录，否则程序可能找不到：

```text
config_client.py
```

或其他资源文件。

### 条件和设置

在“条件”选项卡中：

- 笔记本电脑用户可以取消“仅当计算机使用交流电源时才启动任务”
- 台式机通常可以保持默认

在“设置”选项卡中建议：

- 勾选“允许按需运行任务”
- 勾选“如果任务失败，按以下时间间隔重新启动”
- 重试间隔可设置为：

```text
1 分钟
```

- 重试次数可设置为：

```text
3 次
```

点击“确定”保存任务。

如果系统要求密码，输入当前 Windows 用户密码。

---

## 十七、测试 Windows 客户端自启

在任务计划程序中找到：

```text
CapsWriter Client
```

右键点击“运行”。

确认：

- 客户端能够启动
- 没有弹出命令行窗口
- Caps Lock 或鼠标快捷键正常
- 可以连接 NAS 服务端
- NAS 日志出现：

```text
客户端已连接
```

然后注销 Windows 或重启电脑：

```powershell
shutdown /r /t 0
```

重新登录后，客户端应自动在后台启动。

如果客户端带有托盘图标，可以在 Windows 右下角通知区域查看。

---

## 十八、客户端自启故障排查

### 1. 客户端启动但无法连接

检查 `config_client.py`：

```python
addr = 'NAS的实际IP地址'
port = '6016'
```

然后在 Windows 测试：

```powershell
Test-NetConnection 192.168.1.3 -Port 6016
```

应看到：

```text
TcpTestSucceeded : True
```

### 2. 客户端找不到配置文件

检查任务计划程序中的“起始于”是否填写为：

```text
<客户端目录>
```

不要填写：

```text
<客户端目录>\start_client.exe
```

### 3. 开机后没有启动

在任务计划程序中：

1. 找到 `CapsWriter Client`
2. 查看“上次运行结果”
3. 右键选择“运行”测试
4. 检查“历史记录”中的错误信息

同时确认任务触发器设置为：

```text
登录时
```

而不是“启动时”。

### 4. 出现命令行窗口

确认任务使用的是：

```text
start_client.exe
```

而不是：

```text
python start_client.py
```

并且任务的“常规”选项卡已勾选：

```text
隐藏
```

### 5. 修改配置后没有生效

先退出正在运行的客户端，再重新启动：

```text
<客户端目录>\start_client.exe
```

如果仍未生效，确认 `config_client.py` 与 `start_client.exe` 位于同一目录，并检查程序是否实际读取外部配置文件。