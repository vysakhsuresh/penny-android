// Command server is the Penny sync relay: a store-and-forward service for end-to-end
// encrypted event blobs. It never sees plaintext user data.
package main

import (
	"context"
	"crypto/rand"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"penny/server/connectors"
)

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envBool(key string) bool {
	b, _ := strconv.ParseBool(os.Getenv(key))
	return b
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "fatal:", err)
		os.Exit(1)
	}
}

func run() error {
	var (
		addr       = flag.String("addr", env("ADDR", ":8080"), "listen address (env ADDR)")
		dbURL      = flag.String("database-url", env("DATABASE_URL", ""), "Postgres URL (env DATABASE_URL)")
		region     = flag.String("region", env("REGION", "us"), "data region reported to clients: us, eu, in, sg (env REGION)")
		storeKind  = flag.String("store", env("STORE", ""), "memory or postgres (env STORE; default postgres when DATABASE_URL is set)")
		dev        = flag.Bool("dev", envBool("DEV"), "development mode: print sign-in codes to stdout (env DEV)")
		migrate    = flag.Bool("migrate", envBool("MIGRATE"), "apply database migrations at startup (env MIGRATE)")
		trustProxy = flag.Bool("trust-proxy", envBool("TRUST_PROXY"), "use X-Forwarded-For for client IPs (env TRUST_PROXY)")
		connList   = flag.String("connectors", env("CONNECTORS", "sandbox,simplefin"), "enabled bank connectors, comma-separated (env CONNECTORS)")
	)
	flag.Parse()

	log := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	switch *region {
	case "us", "eu", "in", "sg":
	default:
		return fmt.Errorf("unknown region %q (want us, eu, in or sg)", *region)
	}
	if *storeKind == "" {
		*storeKind = "memory"
		if *dbURL != "" {
			*storeKind = "postgres"
		}
	}

	pepper := []byte(os.Getenv("EMAIL_PEPPER"))
	if len(pepper) == 0 {
		if *storeKind == "postgres" && !*dev {
			return errors.New("EMAIL_PEPPER is required with the postgres store (it keys the email hash; keep it stable)")
		}
		pepper = make([]byte, 32)
		_, _ = rand.Read(pepper)
		log.Warn("EMAIL_PEPPER not set; using a random pepper, accounts will not survive a restart")
	}

	var sealer *Sealer
	if v := os.Getenv("CONNECTOR_KEY"); v != "" {
		key, err := ParseConnectorKey(v)
		if err != nil {
			return err
		}
		if sealer, err = NewSealer(key); err != nil {
			return err
		}
	} else if *dev {
		sealer = RandomSealer()
		log.Warn("CONNECTOR_KEY not set; using a random key, bank connections will not survive a restart")
	} else {
		return errors.New("CONNECTOR_KEY is required outside -dev (32 bytes, base64; it encrypts bank credentials at rest)")
	}
	registry, unconfigured, err := connectors.NewRegistry(connectors.ParseList(*connList), connectors.All(time.Now)...)
	if err != nil {
		return err
	}
	for _, id := range unconfigured {
		log.Warn("connector listed in CONNECTORS but not implemented or configured; left disabled", "connector", id)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	var store Store
	switch *storeKind {
	case "memory":
		store = NewMemoryStore()
	case "postgres":
		if *dbURL == "" {
			return errors.New("DATABASE_URL is required for the postgres store")
		}
		pg, err := NewPostgresStore(ctx, *dbURL)
		if err != nil {
			return fmt.Errorf("connect postgres: %w", err)
		}
		if *migrate {
			if err := pg.Migrate(ctx); err != nil {
				pg.Close()
				return err
			}
			log.Info("migrations applied")
		}
		store = pg
	default:
		return fmt.Errorf("unknown store %q", *storeKind)
	}
	defer store.Close()

	var mailer Mailer = LogMailer{Dev: *dev, Out: os.Stdout, Log: log}
	if host := os.Getenv("SMTP_HOST"); host != "" {
		mailer = SMTPMailer{Host: host, Port: env("SMTP_PORT", "587"), User: os.Getenv("SMTP_USER"),
			Pass: os.Getenv("SMTP_PASS"), From: env("SMTP_FROM", "no-reply@localhost")}
	} else if !*dev {
		log.Warn("SMTP_HOST not set and -dev off: sign-in codes cannot be delivered")
	}

	api := NewServer(Options{Store: store, Mailer: mailer, Log: log, Region: *region, Pepper: pepper, TrustProxy: *trustProxy,
		Dev: *dev, Connectors: registry, Sealer: sealer})
	srv := &http.Server{
		Addr:              *addr,
		Handler:           api.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	go func() { // janitor: drop expired codes, advisor links, connect states and dead invites
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if err := store.PurgeExpired(ctx, time.Now()); err != nil {
					log.Warn("purge failed", "err", err)
				}
			}
		}
	}()

	errc := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", *addr, "region", *region, "store", *storeKind, "dev", *dev)
		errc <- srv.ListenAndServe()
	}()
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return srv.Shutdown(sctx)
}
