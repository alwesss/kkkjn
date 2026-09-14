# Soyo Mesaj Yardimcisi v1.3

Android Accessibility tabanli otomasyon aracidir.

## Calisma akisi

1. SOYO `Onerilen` ekranindaki gorunur `Sohbet` kartlarini yukaridan asagi tarar.
2. Son ayarlanan sure icinde mesaj gonderilmemis profile girer. Varsayilan sure 60 dakikadir.
3. Uygulamada ayarladiginiz mesaji mesaj kutusuna yazar ve gonderir.
4. Profile ait zaman damgasini kaydeder.
5. Listeye geri doner ve siradaki gorunur profili isler.
6. Gorunur adaylar bittiginde once listeye accessibility scroll komutu verir; ekran hareket etmezse fiziksel swipe yedegine gecer.
7. Listenin sonuna gelindiginde otomasyon kapanmaz; listeyi basa kadar geri sarar ve yeni bir tur baslatir.
8. Ayni profil sure dolmadan yeniden gorunurse atlanir. Sure dolduktan sonra tekrar islenebilir.

## Hedef uygulama

Varsayilan paket adi: `com.haflla.soulu`

SOYO paket adi farkliysa uygulamadaki `Hedef SOYO paket adi` alanindan degistirebilirsiniz. Debug/staging eki zorunlulugu kaldirilmistir.

## Kurulum

- APK'yi kurun.
- Uygulamayi acin, mesaji ve hedef paket adini girin.
- `ERISILEBILIRLIK AYARLARINI AC` ile `Soyo Mesaj Yardimcisi` servisini etkinlestirin.
- SOYO uygulamasini acip `Onerilen` ekranina gelin.
- Yardimci uygulamaya donup `OTOMASYONU BASLAT` deyin ve tekrar SOYO'ya gecin.

## Derleme

GitHub Actions workflow'u dahildir. Push sonrasi Actions > Build APK icinden APK artifact'ini indirebilirsiniz.

Codespaces'te Gradle kuruluysa:

```bash
gradle assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`


## v1.2 Android uyumluluk notu

- Android 12 ve altinda erisilebilirlik servisini durdurabilen receiver API uyumsuzlugu giderildi.
- Ana ekranda erisilebilirlik servisinin acik/kapali durumu gosterilir.
- Otomasyon, servis kapaliyken baslamis gibi gorunmez; kullaniciyi erisilebilirlik ayarlarina yonlendirir.


## v1.3 surekli calisma ve gonderme duzeltmeleri

- Ayni ekran sayaci artik sohbetten listeye donmeyi kaydirma hatasi sanmiyor; yalnizca gercek kaydirma sonucunu kontrol ediyor.
- Listenin sonunda `running=false` yapilip otomasyon kapatilmiyor. Basa sarip donguye devam ediyor.
- Kaydirma once `ACTION_SCROLL_FORWARD/BACKWARD`, gerekirse daha uzun fiziksel swipe ile yapiliyor.
- Mesaj metni yazildiktan sonra gonder dugmesi icin taze accessibility agaci bekleniyor.
- Gonder dugmesi node click ile calismazsa ekrandaki konumuna fiziksel tap yedegi deneniyor.
- Gonder dugmesi dogrulamasi en fazla 4 denemeye cikarildi; tek profilde takilip tum otomasyonu durdurmuyor.
- Eski ayni metin mesajinin ekranda bulunmasi yeni mesaj gonderildi diye yanlis pozitif sayilmiyor.
