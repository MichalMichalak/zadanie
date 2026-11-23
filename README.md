# Zadanie - Kupony

Notka: Readme wygenerowane przez agenta dostepne jest tu: [README-BY_AGENT](README-BY_AGENT.md).

- Do zbudowania serwisu uzylem DeepSeek Flash 4.1 i Pi Harness. Sam przygotowalem szkielet projektu, plan pracy i glowne zalozenia. Budowalem serwis iteracyjnie - implementacja czesci funkcjonalnosci, testowanie, rewizja kodu, rewizja zalozen i architektury, kolena iteracja.
- Podzielilem uzytkownikow na sprzedajacych i kupujacych. Sprzedajacy tworza kupony, kupujacy uzywaja ich podczas zakupow.
- Zalozenia funkcjonalne spelnione, dwa endpointy: `/create` and `/use`. (szczegoly [README-BY_AGENT -> API](README-BY_AGENT.md#http-api))
  - IP uzytkownika biore z naglowkow HTTP, przy zalozeniu, ze serwis jest dostepny bezposrednio bez rev-proxy. Oczywiscie w przypadku LB/rev-proxy/API Gateway musimy zaplanowac strategie, ktora daje najwieksze prawdopobienstwo uzyskania poprawnego IP.
  - Poniewaz autoryzacja nie jest zaimplementowana, zalozylem, ze id sprzedawcy i kupujacego beda dostarczone w naglowku, podobnie jak w wielu przypadkach dostarczony bylby identyfikator sesji albo JWT. Jest to rozwiazanie tymczasowe, zastepujace wlasciwa autoryzacje. Wolalem tego nie dostarczac w body poniewaz nie ma pewnosci, ze uzytkownik nie podmienilby id.
  - Poczatkowo myslalem o jakichs mechanizmach gwarantujacych idempotency (idempotency id dostarczane przez uzytkownikow), ale jak sie okazalo mozna tego uniknac przez odpowienie zaprojektowanie DB schema.
  - Zeby poznac kraj uzytkownika, strzelam requestem do <https://api.country.is>. Cache'uje odpowiedz zeby zminimalizowac latency i koszt. Cache'uje tylko pelne segmenty sieci C (np. 1.2.3 zamiast 1.2.3.4). W praktyce segment C nie wychodzi poza granice kraju, a zmniejsza zapotrzebowanie na pamiec Redis oraz potencjalnie zwieksza hit/miss ratio. Ale ostatecznie mozna tez cache'wac cale adresy - przy 50M uzytkownikow i unikalnych IP, zapotrzebowanie nie powinno przekroczyc 6GB.
  - Zalozylem, ze serwis uzywa tylko IPv4, latwe do zmiany jesli tak nie jest.
  - Zalozenie, ze kupon powinien byc case-insensitive jest realizowane w bazie, nie w kodzie aplikacji. Id jest zapisywane dokladnie jak zadecyduje sprzedajacy, ale wszystkie porownania SQL nie biora pod uwage wielkosci znakow.
- Uzywam MySQL jako storage. Relacyjnosc i transakcje ulatwiaja semantyke: 1 kupujacy = 1 uzycie kuponu. Nawet przy wiekszej liczbie uzytkownikow, dane powinny zmiescic sie na dysku jednej maszyny. Mozna pomyslec o replikacji, nie trzeba sie przejmowac shard'ami.
  - Zalozylem ze tablica z id sprzedajacych juz jest w systemie. Podobnie tablica kupujacych.
  - Stworzylem tablice na kupony, oraz dodatkowa na historie uzyc, ktora gwarantuje pojedyncze uzycie. (szczegoly [README-BY_AGENT -> Data model](README-BY_AGENT.md#data-model-and-consistency))
- Uzywam Redis jako cache IP->country.
- Aplikacja jest zbudowana przy uzyciu JVM 21, Spring Boot, Gradle. Docker jest wymagany do testow.
- Testy napisal agent ale, po sprawdzeniu, wygladaja OK.
- Klasa [CouponApiStressTest](app/src/test/kotlin/local/exercise/coupons/coupon/web/CouponApiStressTest.kt), zawierajaca kilka dluzszych testow, jest oznaczona jako stress test i uruchamiana oddzielnie.
- W wiekszosci przypadkow testy uzywaja kontenera MySQL i Redis - wieksze prawdopodobienstwo wykrycie bledu, byc moze kosztem kilku sekund na uruchomienie kontenera. Moim zdaniem warto.
- Zbudowalem srodowisko developerskie w katalogu [/dev-env](./dev-env/). Docker Compose uruchamia MySQL i Redis. Plik [.env](dev-env/.env) powinien zawierac credentials i konfiguracje.
- Po zbudowaniu JAR'a, mozemy zbudowac Docker image zeby ulatwic testowanie i wdrozenie na produkcje, ale zakladam, ze to prawdopodobnie i tak bylby jeden z krokow CICD pipeline.
- Serwis wystawia publicznie dostepna usluge na porcie 8080.
- Na porcie 8081, ktory nie powinien byc dostepny publicznie, wystawiony jest actuator oraz enpoint z metrykami dla Prometheus. Nie dodalem zadnych metryk biznesowych bo w sumie nie przychodzi mi do glowy nich interesujacego. Moglibysmy np. analizowac ruch per kraj, ale do tego nie potrzeba metryk z aplikacji. Storage, zakladam, ma metryki dostepne osobno.
- Historia uzycia kuponow, uzywana do gwarantowania 1 kupujacy = 1 kupon, nie jest nigdy czyszczona. Na dluzsza mete warto by pomyslec o jakims rozwiazania do czyszczenia historii w przypadku kiedy kupon juz osiagnal maksymalna ilosc uzyc. Ale to tez zalezy od wymagan biznesowych, czy zalezy nam na trzymaniu historii i przez jaki okres czasu.
