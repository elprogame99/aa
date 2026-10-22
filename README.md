# EMOTUNE Cloud — Render + Google Login + PostgreSQL

Esta es una adaptación web de EMOTUNE para desplegar en Render. Conserva la interfaz HTML y añade inicio de sesión Google del lado servidor y almacenamiento por cuenta en PostgreSQL.

## Qué se sincroniza

- Metadatos de la biblioteca (título, artista, álbum, emoción, tipo, enlaces y campos presentes en la app).
- Playlists, tema, modo, idioma, estadísticas e información de la última reproducción.
- Cada cuenta se identifica por el identificador estable `sub` que entrega Google; los datos no se mezclan entre cuentas.

Los archivos de audio importados desde el disco siguen en IndexedDB del navegador/PC. Este paquete no sube los MP3/MP4 binarios a la nube; solo guarda los metadatos y enlaces. Para reproducir esos archivos en otros dispositivos haría falta añadir almacenamiento de objetos y una función de subida.

## Configuración de Render

Sube el contenido de esta carpeta a un repositorio de GitHub (sin credenciales) y conecta ese repositorio a Render.

En **New + → Web Service** configura:

- **Name:** `emotune-cloud` (si está disponible; si no, elige otro nombre y usa el dominio que Render te asigne).
- **Project:** tu proyecto o workspace de Render.
- **Branch:** `main` (o la rama donde subiste estos archivos).
- **Language / Runtime:** `Docker`.
- **Region:** la misma región que el PostgreSQL de Render.
- **Root Directory:** vacío si `Dockerfile` está en la raíz del repositorio; si subiste el proyecto dentro de una subcarpeta, escribe esa carpeta.
- **Build Command:** dejar vacío; Render usa el `Dockerfile`.
- **Start Command:** dejar vacío; Render usa el `ENTRYPOINT` del `Dockerfile`.
- **Compute:** `Free` para probar si aparece disponible. Los servicios gratuitos pueden dormir tras un periodo sin uso.
- **Health Check Path:** `/healthz`.

### Base de datos

1. En Render crea una base de datos **PostgreSQL**.
2. Copia su **Internal Database URL**.
3. En el Web Service abre **Environment** y crea `DATABASE_URL` con esa URL. El backend acepta el formato `postgres://...` de Render.
4. Usa una base de datos con persistencia para producción. Comprueba las condiciones y duración del plan gratuito antes de guardar datos importantes; los planes gratuitos pueden tener límites y caducidad.

### Variables de entorno del Web Service

Crea estas variables en **Environment** (no las escribas en el código ni las subas a GitHub):

- `DATABASE_URL` = Internal Database URL de PostgreSQL.
- `GOOGLE_CLIENT_ID` = ID del cliente OAuth de Google.
- `GOOGLE_CLIENT_SECRET` = secreto del cliente OAuth de Google.
- `COOKIE_SECURE` = `true`.

## Configurar Google Sign-In

1. Entra en Google Cloud Console y crea/selecciona un proyecto.
2. Configura la pantalla de consentimiento OAuth y añade tu correo como usuario de prueba si la app está en modo de prueba.
3. Crea credenciales OAuth de tipo **Web application**.
4. Después de crear el servicio en Render, copia la URL real, por ejemplo `https://TU-SERVICIO.onrender.com`.
5. En **Authorized JavaScript origins** añade `https://TU-SERVICIO.onrender.com`.
6. En **Authorized redirect URIs** añade exactamente `https://TU-SERVICIO.onrender.com/login/oauth2/code/google`.
7. Copia el Client ID y Client Secret a las variables de entorno de Render y realiza un nuevo deploy.

El dominio y la ruta de redirección deben coincidir exactamente. Si cambias el nombre/dominio del servicio, actualiza Google Cloud Console.

## Prueba

- Abre `https://TU-SERVICIO.onrender.com/`.
- Pulsa **Continuar con Google**.
- Añade una canción o cambia una preferencia.
- Comprueba que aparece el mensaje de guardado en tu cuenta.
- Abre la app en otro navegador e inicia sesión con la misma cuenta para recuperar los metadatos.
- Inicia sesión con otra cuenta para comprobar que la biblioteca no se comparte entre usuarios.

## Límites de esta versión cloud

- Los archivos locales de audio no se cargan a PostgreSQL ni a la nube.
- La descarga de medios que dependía de los ejecutables `.exe` de Windows no forma parte del servicio web alojado en Render.
- La app de escritorio Java original sigue siendo independiente; esta carpeta es la versión web para Render.
