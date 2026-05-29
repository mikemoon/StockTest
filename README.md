# KOSPI 시세목록 조회 프로그램

Windows 도스창(cmd)에서 KOSPI 종목 시세목록을 조회하는 간단한 콘솔 프로그램입니다.

## 실행

```bat
run_kospi.bat
```

또는 Python으로 직접 실행할 수 있습니다.

```bat
python kospi_quotes.py
```

## 사용 예

1페이지, 약 50개 종목 조회:

```bat
run_kospi.bat
```

3페이지 조회:

```bat
run_kospi.bat --pages 3
```

삼성 관련 종목만 검색:

```bat
run_kospi.bat --search 삼성
```

종목코드로 검색:

```bat
run_kospi.bat --search 005930
```

무설치 데스크탑 exe만들기
$env:JAVA_HOME='C:\Program Files\jdk-17'
$env:Path="C:\Program Files\jdk-17\bin;" + $env:Path
.\gradlew.bat :desktop:createDistributable
(생성위치 : StockTest\desktop\build\compose\binaries\main\app\StockPortfolio)

## 참고

- 자료 출처는 네이버 금융 KOSPI 시가총액 페이지입니다.
- 시세는 제공처 정책에 따라 실시간이 아닐 수 있습니다.
- Python 3가 설치되어 있어야 합니다.
