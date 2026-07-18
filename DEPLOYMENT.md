# TASF B2B - Guía de despliegue

Este repositorio contiene dos aplicaciones principales:

- `ALNS_Tasfb2b`: backend Java/Maven con el motor de simulación ALNS y API HTTP.
- `tasf-frontend`: frontend React/Vite servido como archivos estáticos por Nginx.

La arquitectura de despliegue recomendada en la VM es:

```text
Usuario -> Nginx -> /var/www/tasf-frontend
                  -> /api/* proxy a http://127.0.0.1:8090

systemd -> tasf-backend -> Java/Maven backend en puerto 8090
                         -> PostgreSQL/RDS usando DB_URL, DB_USER, DB_PASSWORD
```

## 1. Requisitos de la VM

Instalar o verificar:

```bash
java -version
mvn -version
node -v
npm -v
nginx -v
git --version
```

Versiones esperadas:

- Java 21
- Maven 3.x
- Node.js compatible con el frontend
- Nginx
- Git

Si falta Java 21, instalarlo antes de compilar el backend. El `pom.xml` compila con target 21; si se usa otro JDK aparecerá el error:

```text
invalid target release: 21
```

## 2. Estructura esperada en la VM

El repo debe estar en:

```bash
/opt/tasf-app/dp1e5_0984
```

Clonar si aún no existe:

```bash
sudo mkdir -p /opt/tasf-app
sudo chown -R $USER:$USER /opt/tasf-app
cd /opt/tasf-app
git clone <URL_DEL_REPO> dp1e5_0984
```

Si ya existe:

```bash
cd /opt/tasf-app/dp1e5_0984
git status
git pull
```

## 3. Variables de entorno del backend

El backend necesita estas variables. Crear una sola vez el archivo protegido que
`systemd` leerá automáticamente en cada arranque:

```bash
sudo install -d -m 700 /etc/tasf
sudo nano /etc/tasf/tasf-backend.env
```

Contenido de `/etc/tasf/tasf-backend.env`:

```bash
PORT=8090
DB_URL=jdbc:postgresql://<HOST>:5432/<DATABASE>?sslmode=require
DB_USER=<USUARIO_DB>
DB_PASSWORD=<PASSWORD_DB>
```

Protegerlo después de ingresar los valores reales:

```bash
sudo chmod 600 /etc/tasf/tasf-backend.env
```

No guardar credenciales reales en Git. El archivo queda solo en el servidor y se
mantiene intacto entre actualizaciones del repositorio.
La clave de MapTiles para los países y ciudades en español ya está incluida como valor
predeterminado del backend, por lo que no requiere configuración adicional al desplegar.
`TASF_MAPTILES_API_KEY` sigue disponible únicamente para sobrescribirla si se rota la clave.

Para probar conectividad a la BD:

```bash
nc -vz <HOST_DB> 5432
```

## 4. Servicio systemd del backend

Crear o editar:

```bash
sudo nano /etc/systemd/system/tasf-backend.service
```

Contenido recomendado:

```ini
[Unit]
Description=TASF Backend ALNS
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/tasf-app/dp1e5_0984/ALNS_Tasfb2b
Environment="PORT=8090"
Environment="DB_URL=jdbc:postgresql://<HOST>:5432/<DATABASE>?sslmode=require"
Environment="DB_USER=<USUARIO_DB>"
Environment="DB_PASSWORD=<PASSWORD_DB>"
EnvironmentFile=/etc/tasf/tasf-backend.env
ExecStart=/usr/bin/mvn exec:java
Restart=always
RestartSec=5
User=<USUARIO_VM>

[Install]
WantedBy=multi-user.target
```

Aplicar cambios:

```bash
sudo systemctl daemon-reload
sudo systemctl enable tasf-backend
sudo systemctl restart tasf-backend
```

Ver logs:

```bash
sudo journalctl -u tasf-backend -n 80 --no-pager
```

Verificar que arrancó:

```bash
curl http://127.0.0.1:8090/api/health
```

Respuesta esperada:

```json
{"status":"ok","service":"ALNS simulator"}
```

## 5. Configuracion Nginx

El frontend se sirve desde:

```bash
/var/www/tasf-frontend
```

Crear carpeta:

```bash
sudo mkdir -p /var/www/tasf-frontend
```

Crear o editar el sitio:

```bash
sudo nano /etc/nginx/sites-available/tasf
```

Configuración recomendada:

```nginx
server {
    listen 80;
    server_name _;

    root /var/www/tasf-frontend;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

Habilitar el sitio:

```bash
sudo ln -s /etc/nginx/sites-available/tasf /etc/nginx/sites-enabled/tasf
```

Si ya existe el symlink, no repetirlo. Verificar configuración:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Confirmar que Nginx apunta al puerto correcto:

```bash
sudo nginx -T | grep -n -A10 -B3 "location /api"
```

Debe verse:

```nginx
proxy_pass http://127.0.0.1:8090;
```

## 6. Despliegue del backend

Cada vez que se actualice el repo:

```bash
cd /opt/tasf-app/dp1e5_0984
git pull

cd ALNS_Tasfb2b
mvn clean compile
sudo systemctl restart tasf-backend
```

Verificar:

```bash
sudo journalctl -u tasf-backend -n 80 --no-pager
curl http://127.0.0.1:8090/api/health
```

Si el log muestra:

```text
Simulador ALNS listo en http://localhost:8090/
```

el backend esta arriba.

## 7. Despliegue del frontend

Entrar al frontend:

```bash
cd /opt/tasf-app/dp1e5_0984/tasf-frontend
```

Instalar dependencias solo si es necesario:

```bash
npm ci
```

`npm ci` es recomendable cuando:

- es el primer despliegue;
- cambiaron `package.json` o `package-lock.json`;
- hay errores raros de dependencias;
- se quiere una instalacion limpia y reproducible.

Para verificar si cambiaron dependencias despues de un `git pull`:

```bash
git diff ORIG_HEAD HEAD -- package.json package-lock.json
```

Compilar:

```bash
npm run build
```

Publicar en Nginx:

```bash
sudo rm -rf /var/www/tasf-frontend/*
sudo cp -r dist/* /var/www/tasf-frontend/
sudo nginx -t
sudo systemctl reload nginx
```

## 8. Despliegue completo recomendado

Comandos completos para actualizar backend y frontend:

```bash
cd /opt/tasf-app/dp1e5_0984
git pull

cd /opt/tasf-app/dp1e5_0984/ALNS_Tasfb2b
mvn clean compile
sudo systemctl restart tasf-backend
curl http://127.0.0.1:8090/api/health

cd /opt/tasf-app/dp1e5_0984/tasf-frontend
# Ejecutar npm ci solo si cambiaron package.json o package-lock.json
npm run build
sudo rm -rf /var/www/tasf-frontend/*
sudo cp -r dist/* /var/www/tasf-frontend/
sudo nginx -t
sudo systemctl reload nginx
```

## 9. Verificaciones funcionales

Backend local en VM:

```bash
curl http://127.0.0.1:8090/api/health
curl http://127.0.0.1:8090/api/airports
curl http://127.0.0.1:8090/api/flights
curl http://127.0.0.1:8090/api/shipments
```

Backend via Nginx:

```bash
curl http://localhost/api/health
curl http://localhost/api/airports
```

Servicio:

```bash
sudo systemctl status tasf-backend --no-pager
sudo journalctl -u tasf-backend -n 80 --no-pager
```

Nginx:

```bash
sudo nginx -t
sudo systemctl status nginx --no-pager
```

## 10. Troubleshooting

### Error: Address already in use

Significa que el puerto ya esta ocupado.

Ver que proceso usa el puerto:

```bash
sudo lsof -i :8090
```

Si hay un backend viejo, detenerlo:

```bash
sudo systemctl stop tasf-backend
```

Luego reiniciar:

```bash
sudo systemctl restart tasf-backend
```

### Error 404 al llamar endpoints desde el frontend

Revisar que Nginx esté enviando `/api` al puerto correcto:

```bash
sudo nginx -T | grep -n -A10 -B3 "location /api"
```

Debe apuntar a:

```nginx
proxy_pass http://127.0.0.1:8090;
```

Si apunta a `9090` u otro puerto, corregir el archivo del sitio y recargar:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### Error 500 por conexión a BD

Verificar variables del proceso:

```bash
PID=$(pgrep -f "exec:java" | head -n 1)
sudo tr '\0' '\n' < /proc/$PID/environ | grep -E '^(DB_URL|DB_USER|DB_PASSWORD|PORT)='
```

Si solo aparece `PORT`, faltan variables de BD en el servicio systemd.

Editar:

```bash
sudo nano /etc/systemd/system/tasf-backend.service
sudo systemctl daemon-reload
sudo systemctl restart tasf-backend
```

Revisar conectividad:

```bash
nc -vz <HOST_DB> 5432
```

### El backend compila localmente pero no en la VM

Verificar Java:

```bash
java -version
mvn -version
```

Debe usar Java 21. Si Maven usa otra version, configurar `JAVA_HOME` o instalar JDK 21.

### El frontend no refleja cambios

Recompilar y copiar de nuevo:

```bash
cd /opt/tasf-app/dp1e5_0984/tasf-frontend
npm run build
sudo rm -rf /var/www/tasf-frontend/*
sudo cp -r dist/* /var/www/tasf-frontend/
sudo systemctl reload nginx
```

Luego limpiar cache del navegador o abrir en modo incognito.

## 11. Ejecucion local en Windows

Backend:

```powershell
cd D:\DP1\dp1e5_0984\ALNS_Tasfb2b
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:PORT='8090'
$env:DB_URL='jdbc:postgresql://<HOST>:5432/<DATABASE>?sslmode=require'
$env:DB_USER='<USUARIO_DB>'
$env:DB_PASSWORD='<PASSWORD_DB>'
mvn.cmd compile exec:java
```

Frontend:

```powershell
cd D:\DP1\dp1e5_0984\tasf-frontend
npm install
npm run dev
```

## 12. Notas importantes

- No commitear contraseñas ni URLs con credenciales.
- El backend debe correr en `8090` para coincidir con Nginx.
- El frontend no habla directo con la BD; siempre usa `/api`.
- Si se modifican endpoints del backend, desplegar backend antes o junto con frontend.
- Si se modifican tipos o vistas del frontend, ejecutar `npm run build` antes de copiar a `/var/www/tasf-frontend`.
