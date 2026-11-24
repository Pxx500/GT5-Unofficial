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

**Hel w zamkniętej rurze.** Hel przy 1 barze wrze w okolicy 4,21 K. Jeśli w rurze jest mieszanina, separator odmierza litry pary, nie litry całej zawartości. Powyżej punktu krytycznego (5,1953 K i 2,276 bar) nie ma już dwóch faz do oddzielenia. Hel z injectora nadal wnosi energię odpowiadającą temperaturze danego świata. Do skraplania trzeba odebrać tę energię. Turbina parowa nie przyjmuje helu. Model nie obejmuje nadciekłości ani stałego helu, a zakres temperatur zaczyna się od 2,3 K.

**Gorąca woda pod ciśnieniem.** Komora obsługuje wodę do 1000 K i 500 bar. To zakres modelu, nie wytrzymałość rury. Przy temperaturze co najmniej 647,096 K i ciśnieniu co najmniej 220,64 bar podgląd pokazuje płyn nadkrytyczny. To nadal woda, ale nie ma osobnych porcji cieczy i pary. Extractor nie odda jej jako produktu GT. Zwykła rura pęknie przy takim ciśnieniu.

**Rura wysokociśnieniowa.** Reinforced VFN Pipe ma 100 L pojemności i dopuszcza 350 bar. Assembler EV składa ją z jednej średniej rury i jednej gęstej płyty z Tungstensteel. Limit odcinka odczytasz w WAILA. Jedna zwykła rura albo zwykły właz w tej samej sieci obniża go do 10 bar. Kocioł przylegający do rury oddaje jej ciepło, ale nie staje się częścią objętości ciśnieniowej.

**Sterowanie.** Nastawę, tolerancję i limit porcji regulatora ustawia się w GUI. Regulator obniża ciśnienie, nie pompuje pod górę. Pompa ciepła pracuje na pobranej porcji, a radiator wymienia ciepło z otoczeniem. Przeciwciśnienie na wylocie może zatrzymać oddawanie już przygotowanej porcji.

**Budowa pompy i turbiny.** Każda tworzy pełny multiblok 3×3×3: kontroler, 22 Solid Steel Machine Casings, jeden maintenance hatch, jeden właz wejściowy VFN, jeden wyjściowy VFN i jeden właz elektryczny GT. W pompie jest to energy hatch, w turbinie dynamo hatch. Rury podłącz do włazów VFN, a przewód do włazu elektrycznego. Do obiegu powyżej 10 bar użyj także wzmocnionych włazów VFN: każdy wnosi 100 L, wytrzymuje 350 bar i nie ma hydroforu. W assemblerze EV połącz odpowiedni właz płynowy GT EV ze wzmocnioną rurą VFN.

**Powrót kondensatu.** W GUI pompy cieczy wybierz, co ma utrzymywać: ciśnienie na wylocie, przyrost ciśnienia (oba w cbar) albo objętość pobieraną na jeden skok (L). Ustaw też minimalne ciśnienie na wlocie i odstęp między skokami (domyślnie 20 ticków). Pompa nie ma termostatu. Z mokrego wydechu pobiera ciecz, a na samej parze nie ruszy. Model MV podnosi ciśnienie maksymalnie o 3 bar, a EV o 120 bar. Jeśli pompa EV nie rusza mimo zasilania, sprawdź dwa ograniczenia: koszt całego skoku musi zmieścić się zarówno w budżecie EU na ustawiony okres, jak i w energii zgromadzonej we włazie. Wydłużenie okresu nie powiększa włazu.

**Wirnik turbiny.** Włóż wirnik GT do kontrolera. Turbina może rozprężać suchą parę albo jednorodną wodę w obsługiwanym zakresie gęstym, także nadkrytyczną. W GUI ustaw stopień otwarcia wlotu i minimalne ciśnienie wlotowe. Do produkcji potrzeba spadku ciśnienia i miejsca we włazie dynamo. Pełne dynamo zatrzymuje przepływ bez uszkodzenia wirnika. Pusty slot pozwala na przepływ pary bez produkcji EU, również po pęknięciu wirnika. Niewłaściwy przedmiot w slocie blokuje pracę.

Przegrzanie pary przed turbiną może ograniczyć uszkodzenia: jeśli para zaczyna się skraplać już podczas oddawania pracy, wirnik dostaje większe uszkodzenie. Odczyt fazy w rurze wylotowej opisuje całą rurę, nie stan pary przy wirniku. Spójrz na turbinę: po pierwszym obrocie pod obciążeniem WAILA pokazuje ostatnie rozprężenie jako suche albo mokre.

**Rozłączenie.** Odpięcie połączenia kluczem zmienia podział komór. Zniszczenie rury może dodatkowo usunąć przypadającą na nią masę i energię. Samo wyładowanie chunka nie jest zniszczeniem rury.

Szczegóły: [topologia i zapis](IntegratedFluidNetwork_Rewrite.md). Przykłady działających instalacji i polecenia testowe: [Horizon QA](../FULL_GAME_SERVER_QA.md).

