# delima-extensions

Repo ekstensi CloudStream pribadi milik delimacode. Fondasi repo ini memakai
template resmi CloudStream (`recloudstream/TestPlugins`), pola build yang sama
dengan repo komunitas besar: setiap push ke `main` memicu GitHub Actions yang
meng-compile semua provider menjadi berkas `.cs3` dan menyusun `plugins.json`,
lalu menerbitkannya ke branch `builds`.

## Cara kerja

- Satu folder di root = satu provider (contoh: `ArchiveProvider/`).
- Folder provider wajib punya `build.gradle.kts`; `settings.gradle.kts` di root
  akan otomatis memasukkannya ke build, tidak perlu didaftarkan manual.
- GitHub Actions (`.github/workflows/build.yml`) menjalankan
  `./gradlew make makePluginsJson`, lalu menyalin semua `*.cs3` dan
  `build/plugins.json` ke branch `builds` (commit diamend, force push).

## Menambahkan repo ini ke CloudStream

Di aplikasi CloudStream: Settings → Extensions → Add Repository, lalu isi URL
manifest `repo.json` dari branch `builds`:

```
https://raw.githubusercontent.com/delimacode/delima-extensions/builds/repo.json
```

Repo ini **Public**, jadi aplikasi bisa mengambil manifest, `plugins.json`, dan
berkas `.cs3` langsung tanpa autentikasi.

## Menambah provider baru

1. Buat folder baru di root, mis. `NamaProvider/`.
2. Isi `build.gradle.kts` (metadata: `version`, `cloudstream { description,
   authors, status, language, tvTypes, iconUrl }`).
3. Kode di `src/main/kotlin/...`: satu class `MainAPI` (provider) dan satu class
   `CloudstreamPlugin` yang mendaftarkannya, plus `src/main/AndroidManifest.xml`.
4. Commit dan push ke `main`; hasil compile muncul di branch `builds`.

Status di metadata provider: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta.

## Isi saat ini

- `ArchiveProvider` — film domain publik dan Creative Commons dari Internet
  Archive (archive.org): beranda berkategori, pencarian, detail, dan putar
  langsung berkas mp4 dari server archive.org, lengkap dengan pilihan kualitas
  dan subtitle `.srt` bila tersedia.

Provider contoh bawaan template (`ExampleProvider`) sudah dihapus; ia hanya
dipakai untuk membuktikan rantai build pada tahap fondasi.
