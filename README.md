# delima-extensions

Repo ekstensi CloudStream pribadi milik delimacode. Fondasi repo ini memakai
template resmi CloudStream (`recloudstream/TestPlugins`), pola build yang sama
dengan repo komunitas besar: setiap push ke `main` memicu GitHub Actions yang
meng-compile semua provider menjadi berkas `.cs3` dan menyusun `plugins.json`,
lalu menerbitkannya ke branch `builds`.

## Cara kerja

- Satu folder di root = satu provider (contoh: `ExampleProvider/`).
- Folder provider wajib punya `build.gradle.kts`; `settings.gradle.kts` di root
  akan otomatis memasukkannya ke build, tidak perlu didaftarkan manual.
- GitHub Actions (`.github/workflows/build.yml`) menjalankan
  `./gradlew make makePluginsJson`, lalu menyalin semua `*.cs3` dan
  `build/plugins.json` ke branch `builds` (commit diamend, force push).

## Menambahkan repo ini ke CloudStream

Di aplikasi CloudStream: Settings → Extensions → Add Repository, lalu isi URL
mentah `plugins.json` dari branch `builds`:

```
https://raw.githubusercontent.com/delimacode/delima-extensions/builds/plugins.json
```

Catatan penting: repo ini saat ini **Private**. CloudStream mengambil
`plugins.json` dan `.cs3` tanpa autentikasi, jadi selama repo Private tautan di
atas tidak bisa dibaca aplikasi. Sebelum dipakai di perangkat, visibilitas repo
perlu diputuskan (mis. ubah ke Public). Keputusan itu disengaja ditunda, bukan
kelalaian.

## Menambah provider baru

1. Buat folder baru di root, mis. `NamaProvider/`.
2. Isi `build.gradle.kts` (metadata: `version`, `cloudstream { description,
   authors, status, language, tvTypes, iconUrl }`).
3. Kode di `src/main/kotlin/...`: satu class `MainAPI` (provider) dan satu class
   `CloudstreamPlugin` yang mendaftarkannya, plus `src/main/AndroidManifest.xml`.
4. Commit dan push ke `main`; hasil compile muncul di branch `builds`.

Status di metadata provider: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta.

## Isi fondasi saat ini

- `ExampleProvider` — provider contoh bawaan template resmi (bukan provider
  situs nyata), penanda bahwa rantai build bekerja.
- Belum ada provider situs apa pun di repo ini.
