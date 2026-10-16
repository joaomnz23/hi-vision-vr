# Hi-Vision VR

Aplicativo Android em Kotlin para óculos de VR Hi-Vision: mostra a câmera traseira na lente esquerda, detecta objetos em tempo real e aciona um ESP32 por Bluetooth com gesto de joinha e comando de voz.

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API_24+-3DDC84?logo=android)](https://developer.android.com/)
[![Gradle](https://img.shields.io/badge/Gradle-9.3.0-02303A?logo=gradle)](https://gradle.org/)
[![TensorFlow Lite](https://img.shields.io/badge/TensorFlow_Lite-0.4.4-FF6F00?logo=tensorflow)](https://www.tensorflow.org/lite)
[![MediaPipe](https://img.shields.io/badge/MediaPipe-0.10.14-0097A7?logo=google)](https://ai.google.dev/edge/mediapipe)
[![Bluetooth](https://img.shields.io/badge/Bluetooth-SPP-0082FC?logo=bluetooth)](https://www.bluetooth.com/)

<!-- Adicione capturas de tela em docs/screenshots/ e descomente a linha abaixo. -->
<!-- ![Tela do aplicativo](docs/screenshots/tela-app.png) -->

## Sobre

O aplicativo foi feito para ser usado com um óculos de VR em que o celular fica encaixado e a lente esquerda enxerga por um quadrado de 6,7 cm da tela. A câmera traseira é exibida somente nessa área, em modo paisagem, e sobre a imagem são desenhadas as caixas dos objetos detectados.

Além de exibir a câmera, o app atua como controle remoto: ao reconhecer o gesto de joinha, ele escuta o comando de voz "liga" ou "desliga" e envia o resultado por Bluetooth a um ESP32 já pareado.

Fluxo de funcionamento:

1. A câmera traseira gera os quadros, analisados pelos dois modelos de visão ao mesmo tempo.
2. Ao detectar o joinha, o app começa a ouvir por até 6 segundos.
3. O comando reconhecido ("liga" ou "desliga") é enviado ao ESP32 por Bluetooth Clássico.
4. O status de cada etapa aparece em texto sobre a imagem.

## Funcionalidades

- Câmera traseira em tela cheia e modo paisagem, exibida apenas na área da lente esquerda, sem duplicar a imagem do lado direito
- Largura da área calculada a partir do DPI físico da tela (padrão de 6,7 cm), com calibração manual de largura e de deslocamento, salva no aparelho
- Detecção de objetos em tempo real, com caixa colorida por classe, nome em português e porcentagem de confiança (pessoa, TV, mouse, teclado, celular, livro e notebook)
- Reconhecimento do gesto de joinha após 5 detecções com confiança mínima de 60%
- Comando de voz em português do Brasil, que reconhece "liga" e "desliga" sem diferenciar maiúsculas e acentos
- Envio do caractere `1` (liga) ou `0` (desliga) ao ESP32 por Bluetooth Clássico (SPP)
- Intervalo de 3 segundos entre um comando e o próximo gesto
- Tela sempre ligada e permissões solicitadas na abertura do app

## Tecnologias

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin 1.9.24 (Java 17) |
| Plataforma | Android (minSdk 24, targetSdk e compileSdk 34) |
| Build | Android Gradle Plugin 8.5.0, Gradle 9.3.0 (wrapper) |
| Câmera | CameraX 1.3.4 |
| Detecção de objetos | TensorFlow Lite Task Vision 0.4.4, modelo EfficientDet-Lite0 |
| Reconhecimento de gesto | MediaPipe Tasks Vision 0.10.14, modelo Gesture Recognizer |
| Voz | `SpeechRecognizer` do Android (pt-BR) |
| Comunicação | Bluetooth Clássico, perfil SPP |
| Interface | Material Components 1.12.0, ConstraintLayout 2.1.4, AppCompat 1.7.0 |

## Estrutura do projeto

```text
HiVisionVR/
└── app/src/main/
    ├── java/com/example/hivisionvr/
    │   ├── MainActivity.kt           # fluxo principal, permissões, voz e calibração
    │   ├── ObjectDetectorHelper.kt   # detecção de objetos (EfficientDet-Lite0)
    │   ├── GestureHelper.kt          # reconhecimento do joinha (MediaPipe)
    │   ├── BluetoothController.kt    # conexão SPP e envio de 1 ou 0 ao ESP32
    │   ├── OverlayView.kt            # caixas e rótulos sobre a câmera
    │   └── ImageUtils.kt             # conversão e rotação dos quadros
    ├── assets/                       # modelos .tflite e .task
    └── res/layout/activity_main.xml  # visualização da câmera, overlay e status
```

## Hardware

| Componente | Uso |
|---|---|
| Celular Android 7.0 ou superior, com câmera traseira, microfone e Bluetooth | Executa o aplicativo |
| Óculos de VR Hi-Vision | Suporte do celular; a lente esquerda enxerga por um quadrado de 6,7 cm |
| ESP32 com Bluetooth Clássico | Recebe os comandos de liga e desliga |

O firmware do ESP32 não faz parte deste repositório. Ele precisa abrir um serviço serial Bluetooth (`BluetoothSerial`) e interpretar o caractere recebido: `1` liga e `0` desliga. O app só conecta ao ESP32, não faz o pareamento.

## Como executar

### Pré-requisitos

- [Android Studio](https://developer.android.com/studio) com JDK 17 ou superior e o SDK do Android 14 (API 34)
- Celular Android 7.0 ou superior (API 24), em modo desenvolvedor com depuração USB ativada. O app usa câmera, microfone e Bluetooth, então não roda em emulador
- Internet no celular: o reconhecimento de voz usa o serviço online do Google
- ESP32 já pareado nas configurações de Bluetooth do Android

### Instalação

```bash
git clone https://github.com/joaomnz23/HiVisionVR.git
cd HiVisionVR
```

1. Abra a pasta no Android Studio e aguarde a sincronização do Gradle.
2. Conecte o celular por USB.
3. Execute o módulo `app` (botão Run).

<details>
<summary>Passo a passo detalhado</summary>

1. **Gradle.** O wrapper baixa o Gradle 9.3.0 na primeira sincronização, então a primeira abertura precisa de internet.

2. **SDK.** Se o Gradle reclamar do local do SDK, crie ou ajuste o arquivo `local.properties` com `sdk.dir=<caminho do Android SDK>`. O Android Studio faz isso sozinho ao sincronizar.

3. **Modelos.** Os dois modelos já estão em `app/src/main/assets/`, com estes nomes exatos: `efficientdet_lite0.tflite` (detecção de objetos) e `gesture_recognizer.task` (reconhecimento de gesto). Caso precise baixá-los de novo, os links estão no arquivo `app/src/main/assets/LEIA-ME.txt`. O arquivo `.tflite` não pode ser compactado no APK, e o `build.gradle` já está configurado para isso.

4. **Nome do ESP32.** Em `MainActivity.kt`, a constante `ESP32_DEVICE_NAME_CONTAINS` define o trecho do nome do dispositivo pareado que o app procura (padrão: `ESP32`). Ajuste para o nome que o seu ESP32 usa, o mesmo definido em `BluetoothSerial.begin("Nome")` no firmware.

5. **Pela linha de comando.** Gere o APK com `./gradlew assembleDebug` (no Windows, `gradlew.bat assembleDebug`) e instale com `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

</details>

### Primeiro acesso

1. Pareie o ESP32 nas configurações de Bluetooth do Android antes de abrir o app.
2. Abra o app e conceda as permissões de câmera, microfone e Bluetooth (no Android 12 ou superior). Todas são pedidas na abertura.
3. Aguarde a mensagem "Conectado ao [nome]. Aguardando gesto de joinha..." no topo da imagem.
4. Faça o gesto de joinha diante da câmera, espere a mensagem "Ouvindo..." e diga "liga" ou "desliga" em até 6 segundos.

### Calibração da área da lente

A calibração é feita com o app aberto e fica salva no aparelho.

| Ação | Efeito |
|---|---|
| Toque na imagem da câmera | Alterna entre ajustar a largura e o deslocamento |
| Botões de volume | Aumentam ou diminuem o valor em 0,05 cm (largura de 3 a 12 cm, deslocamento de -3 a 3 cm) |
| Toque longo na imagem | Restaura o padrão (largura de 6,7 cm e deslocamento 0) |

## Licença

Distribuído sob a licença MIT. Consulte o arquivo `LICENSE`.

## Autor

[joaomnz23](https://github.com/joaomnz23)
