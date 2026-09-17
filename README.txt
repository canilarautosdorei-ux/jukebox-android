JUKEBOX ANDROID WEBVIEW
URL: https://teste.r777b.site/jukebox

Comportamento:
- O APK carrega a Jukebox diretamente do servidor.
- Atualizações HTML/PHP/JS/CSS no servidor NÃO exigem reinstalar o APK.
- Cookies, login e localStorage do WebView são preservados.
- Se o aplicativo ficar sem uso por 24 horas ou mais, ao voltar para o primeiro plano ele recarrega a URL com LOAD_NO_CACHE para obter a versão mais recente do site.
- O refresh de 24h não apaga cookies nem sessão.
- Fullscreen WebView e suporte a vídeo HTML5/YouTube em custom view.

Para compilar:
1. Android Studio / Android SDK com API 35.
2. Abrir esta pasta como projeto Gradle.
3. Build > Build APK(s).
