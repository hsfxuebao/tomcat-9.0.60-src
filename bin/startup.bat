@echo off
rem Licensed to the Apache Software Foundation (ASF) under one or more
rem contributor license agreements.  See the NOTICE file distributed with
rem this work for additional information regarding copyright ownership.
rem The ASF licenses this file to You under the Apache License, Version 2.0
rem (the "License"); you may not use this file except in compliance with
rem the License.  You may obtain a copy of the License at
rem
rem     http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.

rem ---------------------------------------------------------------------------
rem Start script for the CATALINA Server
rem ---------------------------------------------------------------------------

setlocal

rem Guess CATALINA_HOME if not defined
set "CURRENT_DIR=%cd%" // 将当前的路径赋值给CURRENT_DIR
if not "%CATALINA_HOME%" == "" goto gotHome  // 如果在系统环境变量中有配置CATALINA_HOME则直接跳转到gotHome
set "CATALINA_HOME=%CURRENT_DIR%" //如果没配置则将当前的路径赋值给CATALINA_HOME
if exist "%CATALINA_HOME%\bin\catalina.bat" goto okHome  //如果当前赋值的下由catalina.bat文件的存在则跳转到okHome
cd ..
set "CATALINA_HOME=%cd%"
cd "%CURRENT_DIR%"
:gotHome
if exist "%CATALINA_HOME%\bin\catalina.bat" goto okHome
echo The CATALINA_HOME environment variable is not defined correctly
echo This environment variable is needed to run this program
goto end
:okHome

set "EXECUTABLE=%CATALINA_HOME%\bin\catalina.bat" //将catalina.bat给变量EXECUTABLE

rem Check that target executable exists
if exist "%EXECUTABLE%" goto okExec //EXECUTABLE如果存在直接跳转okExec
echo Cannot find "%EXECUTABLE%" //打印没找到EXECUTABLE的存在
echo This file is needed to run this program
goto end
:okExec

rem Get remaining unshifted command line arguments and save them in the
set CMD_LINE_ARGS=
:setArgs
if ""%1""=="""" goto doneSetArgs //判断有没有参数
set CMD_LINE_ARGS=%CMD_LINE_ARGS% %1 //设置第一个参数
shift
goto setArgs
:doneSetArgs

call "%EXECUTABLE%" start %CMD_LINE_ARGS%  //转发调用catalina.bat文件启动 参数为CMD_LINE_ARGS

:end
