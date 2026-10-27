#!/usr/bin/env bash
#
# rbac-check.sh -- prove the access rules hold against a RUNNING PawHome API.
#
# The unit and MockMvc tests check the rules in isolation and through a mocked
# filter chain. This checks the real thing: real MySQL, real BCrypt, real
# tokens, over HTTP. Run it before a demo, and after any change to
# SecurityConfig.
#
#   1. cd docker && docker compose up -d       (MySQL on 3307)
#   2. cd backend && ./mvnw spring-boot:run    (API on 8081)
#   3. ./rbac-check.sh
#
# Every check prints the status it got and the status it wanted. A wrong status
# is a FAIL even when it is "more secure than expected" -- a 401 where a 200
# belongs is a broken feature, and this script is here to catch both directions.

set -uo pipefail

API="${API:-http://localhost:8081/api/v1}"
PASS=0; FAIL=0

# ---------------------------------------------------------------- plumbing

# code <method> <path> [token] [body]
code() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$method" "$API$path")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body"  ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

# body <method> <path> [token] [body]
body() {
  local method="$1" path="$2" token="${3:-}" data="${4:-}"
  local args=(-s -X "$method" "$API$path")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$data"  ]] && args+=(-H 'Content-Type: application/json' -d "$data")
  curl "${args[@]}"
}

check() {
  local label="$1" got="$2" want="$3"
  if [[ "$got" == "$want" ]]; then
    printf '  \033[32mPASS\033[0m  %-58s %s\n' "$label" "$got"; PASS=$((PASS+1))
  else
    printf '  \033[31mFAIL\033[0m  %-58s %s (wanted %s)\n' "$label" "$got" "$want"; FAIL=$((FAIL+1))
  fi
}

token_from() { python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null; }

# ---------------------------------------------------------------- preflight

echo
echo "PawHome RBAC check  ->  $API"
echo

if [[ "$(code GET /health)" != "200" ]]; then
  echo "  The API is not answering on $API."
  echo "  Start MySQL (cd docker && docker compose up -d) and the app"
  echo "  (cd backend && ./mvnw spring-boot:run), then run this again."
  exit 1
fi

# ---------------------------------------------------------------- anonymous

echo "Anonymous"
check "health is public"                     "$(code GET /health)"        200
check "the guest pet list is public"         "$(code GET /pets)"          200
check "/auth/me without a token is 401"      "$(code GET /auth/me)"       401
check "an admin URL is 401, not 200"         "$(code GET /admin/users)"   401
check "a garbage token is 401, not 500"      "$(code GET /auth/me 'not-a-jwt')" 401
echo

# ---------------------------------------------------------------- registration

echo "Registration rules"
STAMP=$(date +%s)
ADOPTER_EMAIL="adopter.$STAMP@example.com"
STAFF_EMAIL="staff.$STAMP@example.com"

check "self-registering as ADMIN is refused" \
  "$(code POST /auth/register '' "{\"fullName\":\"Sneaky\",\"email\":\"admin.$STAMP@x.com\",\"password\":\"correcthorsebattery\",\"role\":\"ADMIN\"}")" 403

check "an adopter may register" \
  "$(code POST /auth/register '' "{\"fullName\":\"Test Adopter\",\"email\":\"$ADOPTER_EMAIL\",\"password\":\"correcthorsebattery\",\"role\":\"ADOPTER\"}")" 201

check "a duplicate email is refused" \
  "$(code POST /auth/register '' "{\"fullName\":\"Again\",\"email\":\"$ADOPTER_EMAIL\",\"password\":\"correcthorsebattery\",\"role\":\"ADOPTER\"}")" 409

check "an adopter may not claim a shelter" \
  "$(code POST /auth/register '' "{\"fullName\":\"X\",\"email\":\"x.$STAMP@x.com\",\"password\":\"correcthorsebattery\",\"role\":\"ADOPTER\",\"shelterId\":1}")" 400

check "staff must name a shelter" \
  "$(code POST /auth/register '' "{\"fullName\":\"Y\",\"email\":\"y.$STAMP@x.com\",\"password\":\"correcthorsebattery\",\"role\":\"STAFF\"}")" 400

check "a short password is refused" \
  "$(code POST /auth/register '' "{\"fullName\":\"Z\",\"email\":\"z.$STAMP@x.com\",\"password\":\"short\",\"role\":\"ADOPTER\"}")" 400

check "a staff sign-up is accepted (and lands PENDING)" \
  "$(code POST /auth/register '' "{\"fullName\":\"Test Staff\",\"email\":\"$STAFF_EMAIL\",\"password\":\"correcthorsebattery\",\"role\":\"STAFF\",\"shelterId\":1}")" 201
echo

# ---------------------------------------------------------------- login

echo "Login"
check "a wrong password is 401" \
  "$(code POST /auth/login '' "{\"email\":\"$ADOPTER_EMAIL\",\"password\":\"wrongwrongwrong\"}")" 401

check "an unknown email is ALSO 401, with the same shape" \
  "$(code POST /auth/login '' "{\"email\":\"nobody.$STAMP@example.com\",\"password\":\"wrongwrongwrong\"}")" 401

# The PENDING staff member has the right password and must still be refused.
check "a PENDING staff member cannot sign in" \
  "$(code POST /auth/login '' "{\"email\":\"$STAFF_EMAIL\",\"password\":\"correcthorsebattery\"}")" 403

ADOPTER_TOKEN=$(body POST /auth/login '' \
  "{\"email\":\"$ADOPTER_EMAIL\",\"password\":\"correcthorsebattery\"}" | token_from)

if [[ -z "$ADOPTER_TOKEN" ]]; then
  echo "  Could not obtain an adopter token; the checks below cannot run."
  FAIL=$((FAIL+1))
else
  check "an adopter can sign in and use the token" "$(code GET /auth/me "$ADOPTER_TOKEN")" 200
fi
echo

# ---------------------------------------------------------------- role matrix

echo "Role matrix"
if [[ -n "$ADOPTER_TOKEN" ]]; then
  check "adopter -> /admin/**  is 403"  "$(code GET /admin/users "$ADOPTER_TOKEN")" 403
  check "adopter -> /staff/**  is 403"  "$(code GET /staff/pets  "$ADOPTER_TOKEN")" 403
fi

# The seeded admin from V2__seed_reference_data.sql.
ADMIN_TOKEN=$(body POST /auth/login '' \
  '{"email":"admin@pawhome.local","password":"admin123"}' | token_from)

if [[ -z "$ADMIN_TOKEN" ]]; then
  echo "  Could not sign in as the seeded admin (admin@pawhome.local / admin123)."
  echo "  If you have changed that password, the admin checks below are skipped."
else
  check "admin can sign in"             "$(code GET /auth/me "$ADMIN_TOKEN")" 200
  # These two expect a real endpoint. Until /admin/users exists, a 404 here
  # means "authorised, nothing to serve" -- which still proves the rule let it
  # through. Change to 200 once the controller lands.
  ADMIN_HIT=$(code GET /admin/users "$ADMIN_TOKEN")
  if [[ "$ADMIN_HIT" == "404" ]]; then
    printf '  \033[33mNOTE\033[0m  %-58s %s\n' "admin -> /admin/** got through (endpoint not written yet)" "$ADMIN_HIT"
  else
    check "admin -> /admin/** is allowed" "$ADMIN_HIT" 200
  fi
fi
echo

# ---------------------------------------------------------------- summary

echo "-----------------------------------------------------------------"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
echo
[[ "$FAIL" -eq 0 ]] || exit 1
