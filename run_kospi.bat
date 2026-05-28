@echo off
chcp 65001 > nul

where python > nul 2> nul
if errorlevel 1 goto use_py

python "%~dp0kospi_quotes.py" %*
goto done

:use_py
py -3 "%~dp0kospi_quotes.py" %*

:done
if errorlevel 1 echo ??? ??????? ?????????.
