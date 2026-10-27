#!/usr/bin/env bash
# ============================================================================
# Show ONE transaction's SQL, and nothing else.
#
#     ./mvnw spring-boot:run -Dspring-boot.run.profiles=sqllog 2>&1 | ./watch-approve.sh
#
# WHY THIS EXISTS. With the sqllog profile on, every request logs a handful of
# EntityManager lines and a users lookup (JwtAuthFilter loads the caller on
# every single call), so the four statements you actually want are buried in a
# few hundred lines of housekeeping. Clicking Approve and then scrolling to
# find them is not something to do in front of a marker.
#
# This keeps only: transaction boundaries, statements, and bound values.
# ============================================================================
awk '
  # --- transaction boundaries, printed as banners ---------------------------
  /Creating new transaction with name/ {
      # Anchor on "with name [" -- the FIRST [...] on the line is the logger
      # thread tag [pawhome-backend], not the method, so an unanchored match
      # captures the wrong thing and the skip below never fires.
      match($0, /with name \[[^]]*\]/)
      m = substr($0, RSTART+11, RLENGTH-12)
      # skip the framework noise: SimpleJpaRepository.findById fires on every
      # request from the auth filter and is never what you are looking at.
      if (m ~ /SimpleJpaRepository/) { skip = 1; next }
      skip = 0
      printf "\n╔══ BEGIN  %s\n", m
      next
  }
  /Initiating transaction commit/   { if (!skip) print  "╚══ COMMIT"; next }
  /Initiating transaction rollback/ { if (!skip) print  "╚══ ROLLBACK  <-- nothing above was kept"; next }

  # --- statements -----------------------------------------------------------
  # org.hibernate.SQL and JdbcTemplate both end their line with the statement
  # starting on the NEXT line (format_sql indents it), so set a flag.
  /org\.hibernate\.SQL/                      { if (skip) { inSql = 0; next } inSql = 1; print "  ┌ [hibernate]"; next }
  /o\.s\.jdbc\.core\.JdbcTemplate.*Executing/ { if (skip) next; print "  ┌ [jdbc] " substr($0, index($0, "Executing")); next }

  # --- bound parameters -----------------------------------------------------
  /binding parameter/ {
      if (skip) next
      match($0, /\(.*\) <- \[.*\]/)
      print "      bind " substr($0, RSTART, RLENGTH)
      inSql = 0
      next
  }
  /StatementCreatorUtils.*Setting SQL statement parameter/ {
      print "      bind " substr($0, index($0, "value"))
      next
  }

  # --- the indented statement body ------------------------------------------
  inSql && !skip && /^[[:space:]]+[A-Za-z(*]/ { print "  │ " $0; next }
  inSql && /^[[:space:]]*$/          { inSql = 0; next }

  # --- keep the application log lines, drop everything else -----------------
  /c\.p\.b\./ { print "  » " substr($0, index($0, ": ") + 2); next }
'
