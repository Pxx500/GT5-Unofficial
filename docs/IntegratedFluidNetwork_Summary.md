# Virtual Fluid Network

VFN pozwala zbudować obiegi parowe, gazowe i chłodnicze z rur, włazów i maszyn GregTecha. Połączony odcinek rur jest jedną komorą. Aby utrzymać różne temperatury lub ciśnienia, rozdziel odcinki portami maszyny albo regulatorem.

| Odczyt | Co oznacza |
| --- | --- |
| Masa (kg) | Ilość substancji. Skroplenie pary nie zmienia jej masy. |
| Objętość (L) | Miejsce w rurach. Włazy mogą również wnosić pojemność hydroforu. |
| Temperatura (K), ciśnienie (bar) | Wartości wynikające z masy, energii i dostępnej objętości. Próżnia ma 0 bar i nie ma temperatury płynu. |
| Faza | Woda może być cieczą, parą, lodem lub ich mieszaniną. Blokada sieci nie oznacza zamarznięcia wody. |

**Wejście z GT.** Injector przyjmuje wodę, tlen, wodór, azot, hel, chłodziwo IC2 i supercoolant w temperaturze otoczenia danego wymiaru. Supercoolant nie schłodzi obiegu samą nazwą. Wyjątkiem jest zwykła para GT: na wejściu jest parą nasyconą o temperaturze 100°C. Jeden litr wody GT wnosi 1 kg. Tę samą masę wnosi 160 litrów pary GT. Po wpłynięciu do komory temperatura i faza zależą od jej zawartości i objętości. Injector nie przyjmuje stosów z NBT ani gorących odmian chłodziw.

**Wyjście do GT.** Extractor wymaga odpowiedniej fazy, energii i nadwyżki ciśnienia. Samo podłączenie pustego zbiornika nie podgrzewa ani nie spręża płynu. Przy przepływie między dwoma obiegami VFN maszyny korzystają bezpośrednio z ich komór, bez zamiany na stos GT.

**Gorąca woda pod ciśnieniem.** Komora obsługuje wodę do 1000 K i 500 bar. To zakres modelu, nie wytrzymałość rury. Przy temperaturze co najmniej 647,096 K i ciśnieniu co najmniej 220,64 bar podgląd pokazuje płyn nadkrytyczny. To nadal woda, ale nie ma osobnych porcji cieczy i pary. Extractor nie odda jej jako produktu GT. Zwykła rura pęknie przy takim ciśnieniu.

**Rura wysokociśnieniowa.** Reinforced VFN Pipe ma 100 L pojemności i dopuszcza 350 bar. Assembler EV składa ją z jednej średniej rury i jednej gęstej płyty z Tungstensteel. Limit odcinka odczytasz w WAILA. Jedna zwykła rura albo zwykły właz w tej samej sieci obniża go do 10 bar. Kocioł przylegający do rury oddaje jej ciepło, ale nie staje się częścią objętości ciśnieniowej.

**Sterowanie.** Nastawę, tolerancję i limit porcji regulatora ustawia się w GUI. Regulator obniża ciśnienie, nie pompuje pod górę. Pompa ciepła pracuje na pobranej porcji, a radiator wymienia ciepło z otoczeniem. Przeciwciśnienie na wylocie może zatrzymać oddawanie już przygotowanej porcji.

**Powrót kondensatu.** Pompa cieczy MV pobiera ciecz z tyłu i tłoczy ją do sieci z przodu. Prąd doprowadź z boku. W GUI wybierz ciśnienie wylotowe, przyrost ciśnienia (cbar) albo przepływ (L/t). To limity: rzeczywista porcja zależy od ilości cieczy, ciśnienia odbiornika i dostępnych EU. Pompa nie ma termostatu. Z mieszaniny cieczy i pary pobiera ciecz, więc na suchym wydechu turbiny nie ruszy. Przed wlotem turbiny utrzymuj suchą parę, a skraplanie zaplanuj za turbiną.

**Wirnik turbiny.** Włóż wirnik GT do slotu turbiny parowej. Turbina ze sprawnym wirnikiem potrzebuje suchej pary na wlocie, spadku ciśnienia i miejsca na wytworzone EU. Brak odbioru energii zatrzymuje przepływ bez uszkadzania wirnika. Bez sprawnego wirnika para może przepływać zgodnie ze spadkiem ciśnienia, ale nie daje EU. Dotyczy to także chwili po pęknięciu wirnika.

Przegrzanie pary przed turbiną może ograniczyć uszkodzenia: jeśli para zaczyna się skraplać już podczas oddawania pracy, wirnik dostaje większe uszkodzenie. Odczyt fazy w rurze wylotowej opisuje całą rurę, nie stan pary przy wirniku. Spójrz na turbinę: po pierwszym obrocie pod obciążeniem WAILA pokazuje ostatnie rozprężenie jako suche albo mokre.

**Rozłączenie.** Odpięcie połączenia kluczem zmienia podział komór. Zniszczenie rury może dodatkowo usunąć przypadającą na nią masę i energię. Samo wyładowanie chunka nie jest zniszczeniem rury.

Szczegóły: [topologia i zapis](IntegratedFluidNetwork_Rewrite.md). Przykłady działających instalacji i polecenia testowe: [Horizon QA](../FULL_GAME_SERVER_QA.md).

