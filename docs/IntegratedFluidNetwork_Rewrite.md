# Topologia i zapis VFN

`NetworkManager` zarządza połączeniami w obrębie świata serwera. Rury i włazy zgłaszają dodanie, utratę połączenia i usunięcie. Menedżer znajduje spójne grupy członków. Każda grupa ma własną sieć i zapas substancji.

## Co jest zapisywane

Zapas komory określają identyfikator substancji, masa w milionowych częściach kilograma (mg) i całkowita energia w mikro-EU. Energia obejmuje energię wewnętrzną płynu oraz pracę zmagazynowaną w hydroforze. Zapis sieci zawiera również jej UUID, oczekiwaną liczbę członków, geometrię i ewentualną przyczynę blokady. Temperatura i ciśnienie nie są osobnymi wartościami do odtworzenia.

Po ustaleniu geometrii sieć wyznacza stan płynu. Pusta komora ma zerowe ciśnienie absolutne. Nieobsługiwana substancja nie dostaje właściwości wody. Nie ma migracji zapisów ze starego formatu opartego na entalpii.

## Łączenie i dzielenie

- **Połączenie:** sprawdź zgodność substancji i zakres zapisu. Dodaj masy i energie, następnie oblicz stan dla połączonej geometrii. Nie uśredniaj temperatur ani ciśnień.
- **Rozpięcie:** przy znanym stanie płynu rozdziel masę według zajmowanej objętości. Każdy hydrofor zachowuje pracę przypadającą na jego część. Zaokrąglenie do jednostek zapisu może być stratne.
- **Zniszczenie:** zachowane odcinki nie odzyskują udziału zniszczonego elementu. Przekroczenie progu rozerwania może spowodować awarię rury.
- **Wyładowanie chunka:** oczekiwany, lecz nieobecny członek sieci nie jest traktowany jako rozbita rura. Niekompletna sieć nie prowadzi normalnego transferu przez porty maszyn.

## Granica maszyny

Stan maszyny i stan sieci są odrębne. Pobrana porcja należy do komory maszyny. Po zatrzymaniu pompy ciepła nie wolno ponownie pobrać ani płynu, ani już rozliczonych EU. Spadek ciśnienia na wylocie może umożliwić oddanie porcji, której wcześniej nie można było wypchnąć.

W integracjach korzystaj z [interfejsów sieci i portów](NetworkManager_Usage.md). Zachowanie instalacji sprawdza [zestaw serwerowy](../FULL_GAME_SERVER_QA.md), w tym osobny test ponownego uruchomienia serwera.

