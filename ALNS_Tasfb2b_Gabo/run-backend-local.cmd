@echo off
setlocal

set "DB_URL=jdbc:postgresql://tasf-b2b-db.cosjdnbzs55n.us-east-1.rds.amazonaws.com:5432/tasf_b2b?sslmode=require"
set "DB_USER=postgres"
set "DB_PASSWORD=iHBJclS5mXmHVTYhGles"
set "AWS_REGION=us-east-1"

if exist "C:\Program Files\Java\jdk-21.0.11\bin\java.exe" (
  set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.11"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

mvn compile exec:java

endlocal
