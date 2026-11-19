package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/argon2"
)

const (
	maxBackupBytes = 16 << 20
	sessionAge     = 30 * 24 * time.Hour
)

var usernamePattern = regexp.MustCompile(`^[a-zA-Z0-9._-]{3,32}$`)
var passwordHashSlots = make(chan struct{}, 2)

type user struct {
	Salt string `json:"salt"`
	Hash string `json:"hash"`
}

type session struct {
	User      string    `json:"user"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type syncRecord struct {
	Revision uint64          `json:"revision"`
	Data     json.RawMessage `json:"data"`
}

type store struct {
	Users    map[string]user       `json:"users"`
	Sessions map[string]session    `json:"sessions"`
	Sync     map[string]syncRecord `json:"sync"`
}

type app struct {
	mu          sync.Mutex
	file        string
	data        store
	loginMu     sync.Mutex
	loginLimits map[string]loginWindow
	dummySalt   []byte
	dummyHash   []byte
}

type loginWindow struct {
	Started  time.Time
	Failures int
}

func main() {
	path := os.Getenv("UNISON_DATA_FILE")
	if path == "" {
		path = "/data/unison.json"
	}
	a := &app{file: path, loginLimits: make(map[string]loginWindow)}
	a.dummySalt = make([]byte, 16)
	if _, err := rand.Read(a.dummySalt); err != nil {
		log.Fatal(err)
	}
	a.dummyHash = argon2.IDKey([]byte("unison-invalid-account"), a.dummySalt, 3, 64*1024, 2, 32)
	if err := a.load(); err != nil {
		log.Fatal(err)
	}
	if len(os.Args) > 1 && (os.Args[1] == "create-user" || os.Args[1] == "delete-user") {
		var err error
		if os.Args[1] == "create-user" {
			err = a.createUserFromStdin()
		} else {
			err = a.deleteUserFromStdin()
		}
		if err != nil {
			log.Fatal(err)
		}
		return
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /v1/auth/login", a.login)
	mux.HandleFunc("POST /v1/auth/logout", a.auth(a.logout))
	mux.HandleFunc("GET /v1/sync", a.auth(a.getSync))
	mux.HandleFunc("PUT /v1/sync", a.auth(a.putSync))
	server := &http.Server{
		Addr:              ":8080",
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	log.Printf("Unison API listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}

func (a *app) load() error {
	if err := os.MkdirAll(filepath.Dir(a.file), 0700); err != nil {
		return err
	}
	b, err := os.ReadFile(a.file)
	if errors.Is(err, os.ErrNotExist) {
		a.data = emptyStore()
		return a.persist()
	}
	if err != nil {
		return err
	}
	if err := json.Unmarshal(b, &a.data); err != nil {
		return fmt.Errorf("read data file: %w", err)
	}
	if a.data.Users == nil {
		a.data = emptyStore()
	}
	if a.data.Sessions == nil {
		a.data.Sessions = make(map[string]session)
	}
	if a.data.Sync == nil {
		a.data.Sync = make(map[string]syncRecord)
	}
	return nil
}

func emptyStore() store {
	return store{Users: make(map[string]user), Sessions: make(map[string]session), Sync: make(map[string]syncRecord)}
}

func (a *app) persist() error {
	b, err := json.Marshal(a.data)
	if err != nil {
		return err
	}
	tmp := a.file + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, a.file)
}

func (a *app) createUserFromStdin() error {
	in := bufio.NewReader(os.Stdin)
	fmt.Print("Username (3-32 letters, digits, . _ -): ")
	name, err := in.ReadString('\n')
	if err != nil {
		return err
	}
	name = strings.TrimSpace(name)
	if !usernamePattern.MatchString(name) {
		return errors.New("invalid username")
	}
	fmt.Print("Password (12+ characters): ")
	password, err := readHidden(in)
	if err != nil {
		return err
	}
	fmt.Print("Confirm password: ")
	confirmation, err := readHidden(in)
	if err != nil {
		return err
	}
	if password != confirmation {
		return errors.New("passwords do not match")
	}
	if len(password) < 12 || len(password) > 1024 {
		return errors.New("password must be 12-1024 characters")
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return err
	}
	hash := argon2.IDKey([]byte(password), salt, 3, 64*1024, 2, 32)
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, exists := a.data.Users[name]; exists {
		return errors.New("user already exists")
	}
	a.data.Users[name] = user{Salt: hex.EncodeToString(salt), Hash: hex.EncodeToString(hash)}
	if err := a.persist(); err != nil {
		delete(a.data.Users, name)
		return err
	}
	fmt.Println("User created.")
	return nil
}

func readHidden(in *bufio.Reader) (string, error) {
	setEcho := func(enabled bool) error {
		arg := "-echo"
		if enabled {
			arg = "echo"
		}
		cmd := exec.Command("stty", arg)
		cmd.Stdin = os.Stdin
		cmd.Stdout = os.Stdout
		return cmd.Run()
	}
	if err := setEcho(false); err != nil {
		return "", errors.New("account creation requires an interactive SSH terminal")
	}
	line, readErr := in.ReadString('\n')
	_ = setEcho(true)
	fmt.Println()
	return strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r"), readErr
}

func (a *app) deleteUserFromStdin() error {
	in := bufio.NewReader(os.Stdin)
	fmt.Print("Username to permanently delete: ")
	name, err := in.ReadString('\n')
	if err != nil {
		return err
	}
	name = strings.TrimSpace(name)
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, exists := a.data.Users[name]; !exists {
		return errors.New("user not found")
	}
	oldUser := a.data.Users[name]
	oldSync, hadSync := a.data.Sync[name]
	delete(a.data.Users, name)
	delete(a.data.Sync, name)
	for key, value := range a.data.Sessions {
		if value.User == name {
			delete(a.data.Sessions, key)
		}
	}
	if err := a.persist(); err != nil {
		a.data.Users[name] = oldUser
		if hadSync {
			a.data.Sync[name] = oldSync
		}
		return err
	}
	fmt.Println("User and their sync data deleted.")
	return nil
}

func (a *app) login(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if !decodeJSON(w, r, &body, 8<<10) || !usernamePattern.MatchString(body.Username) || body.Password == "" {
		writeError(w, http.StatusBadRequest, "Укажи имя пользователя и пароль.")
		return
	}
	limitKey := clientIP(r) + "|" + body.Username
	if !a.allowLogin(limitKey) {
		writeError(w, http.StatusTooManyRequests, "Слишком много попыток входа. Подожди 15 минут.")
		return
	}
	select {
	case passwordHashSlots <- struct{}{}:
		defer func() { <-passwordHashSlots }()
	case <-r.Context().Done():
		return
	}
	a.mu.Lock()
	u, ok := a.data.Users[body.Username]
	a.mu.Unlock()
	salt, want := a.dummySalt, a.dummyHash
	if ok {
		decodedSalt, saltErr := hex.DecodeString(u.Salt)
		decodedHash, hashErr := hex.DecodeString(u.Hash)
		if saltErr == nil && hashErr == nil && len(decodedSalt) >= 16 && len(decodedHash) == 32 {
			salt, want = decodedSalt, decodedHash
		} else {
			ok = false
		}
	}
	got := argon2.IDKey([]byte(body.Password), salt, 3, 64*1024, 2, uint32(len(want)))
	valid := ok && subtle.ConstantTimeCompare(got, want) == 1
	if !valid {
		a.recordLoginFailure(limitKey)
		writeError(w, http.StatusUnauthorized, "Неверный логин или пароль.")
		return
	}
	a.clearLoginFailures(limitKey)
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		writeError(w, http.StatusInternalServerError, "Не удалось создать сессию.")
		return
	}
	token := base64.RawURLEncoding.EncodeToString(secret)
	hash := sha256.Sum256([]byte(token))
	a.mu.Lock()
	a.data.Sessions[hex.EncodeToString(hash[:])] = session{User: body.Username, ExpiresAt: time.Now().Add(sessionAge)}
	err := a.persist()
	if err != nil {
		delete(a.data.Sessions, hex.EncodeToString(hash[:]))
	}
	a.mu.Unlock()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "Не удалось сохранить сессию.")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "expiresAt": time.Now().Add(sessionAge).UTC()})
}

func (a *app) auth(next func(http.ResponseWriter, *http.Request, string)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		header := strings.TrimSpace(r.Header.Get("Authorization"))
		if !strings.HasPrefix(header, "Bearer ") {
			writeError(w, http.StatusUnauthorized, "Войди в аккаунт Unison.")
			return
		}
		token := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
		hash := sha256.Sum256([]byte(token))
		hashKey := hex.EncodeToString(hash[:])
		a.mu.Lock()
		s, ok := a.data.Sessions[hashKey]
		if ok && time.Now().After(s.ExpiresAt) {
			delete(a.data.Sessions, hashKey)
			_ = a.persist()
			ok = false
		}
		a.mu.Unlock()
		if !ok {
			writeError(w, http.StatusUnauthorized, "Сессия истекла. Войди снова.")
			return
		}
		next(w, r, s.User)
	}
}

func (a *app) logout(w http.ResponseWriter, r *http.Request, _ string) {
	token := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(r.Header.Get("Authorization")), "Bearer "))
	hash := sha256.Sum256([]byte(token))
	a.mu.Lock()
	delete(a.data.Sessions, hex.EncodeToString(hash[:]))
	err := a.persist()
	a.mu.Unlock()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "Не удалось завершить сессию.")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) getSync(w http.ResponseWriter, _ *http.Request, name string) {
	a.mu.Lock()
	record, ok := a.data.Sync[name]
	a.mu.Unlock()
	if !ok || len(record.Data) == 0 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	w.Header().Set("ETag", fmt.Sprintf("\"%d\"", record.Revision))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(record.Data)
}

func (a *app) putSync(w http.ResponseWriter, r *http.Request, name string) {
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBackupBytes))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "Файл синхронизации слишком большой.")
		return
	}
	var backup struct {
		App string `json:"app"`
	}
	if !json.Valid(b) || json.Unmarshal(b, &backup) != nil || backup.App != "Unison" {
		writeError(w, http.StatusBadRequest, "Ожидалась резервная копия Unison в формате JSON.")
		return
	}
	match := r.Header.Get("If-Match")
	noneMatch := r.Header.Get("If-None-Match")
	a.mu.Lock()
	current, exists := a.data.Sync[name]
	if exists {
		want := fmt.Sprintf("\"%d\"", current.Revision)
		if match != want {
			a.mu.Unlock()
			writeError(w, http.StatusConflict, "Данные изменились на другом устройстве. Синхронизируй ещё раз.")
			return
		}
	} else if noneMatch != "*" {
		a.mu.Unlock()
		writeError(w, http.StatusConflict, "Копии пока нет. Повтори синхронизацию.")
		return
	}
	revision := current.Revision + 1
	a.data.Sync[name] = syncRecord{Revision: revision, Data: json.RawMessage(b)}
	if err := a.persist(); err != nil {
		a.data.Sync[name] = current
		a.mu.Unlock()
		writeError(w, http.StatusInternalServerError, "Не удалось сохранить синхронизацию.")
		return
	}
	a.mu.Unlock()
	w.Header().Set("ETag", fmt.Sprintf("\"%d\"", revision))
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) allowLogin(ip string) bool {
	a.loginMu.Lock()
	defer a.loginMu.Unlock()
	if len(a.loginLimits) > 1024 {
		for key, entry := range a.loginLimits {
			if time.Since(entry.Started) >= 15*time.Minute {
				delete(a.loginLimits, key)
			}
		}
	}
	w := a.loginLimits[ip]
	if time.Since(w.Started) >= 15*time.Minute {
		w = loginWindow{Started: time.Now()}
	}
	return w.Failures < 10
}

func (a *app) recordLoginFailure(ip string) {
	a.loginMu.Lock()
	defer a.loginMu.Unlock()
	w := a.loginLimits[ip]
	if time.Since(w.Started) >= 15*time.Minute {
		w = loginWindow{Started: time.Now()}
	}
	w.Failures++
	a.loginLimits[ip] = w
}

func (a *app) clearLoginFailures(ip string) {
	a.loginMu.Lock()
	delete(a.loginLimits, ip)
	a.loginMu.Unlock()
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any, limit int64) bool {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(target); err != nil {
		return false
	}
	return dec.Decode(new(any)) == io.EOF
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
