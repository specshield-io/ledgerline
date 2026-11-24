#!/usr/bin/env bash
# Collects the evidence that other workflows produce, for evidence/assemble.mjs:
#   external/k6/{summary.json,verify.json,environment.txt}  latest successful load-test.yml run on main (LL-N01)
#   external/zap/report_json.json                          latest completed zap-baseline.yml run on main (LL-N04)
#   external/codeql.txt                                    pass | fail (latest CodeQL run, open high/critical alerts)
#   external/sources.json                                  links to the runs the numbers came from
# Anything not found is simply absent, and the evidence shows null for it. Needs GH_TOKEN and REPO.
set -uo pipefail
mkdir -p external/k6 external/zap
sources="{}"

add_source() { sources=$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); d[sys.argv[2]]=sys.argv[3]; print(json.dumps(d))' "$sources" "$1" "$2"); }

latest_run() { # workflow file, extra filter
  gh run list -R "$REPO" --workflow "$1" --branch main $2 --limit 1 --json databaseId,url,conclusion \
    --jq '.[0] | select(. != null) | "\(.databaseId) \(.url) \(.conclusion)"' 2>/dev/null
}

if read -r id url _ < <(latest_run load-test.yml "--status success"); then
  gh run download "$id" -R "$REPO" -n k6-results -D external/k6 && add_source load "$url"
fi

if read -r id url _ < <(latest_run zap-baseline.yml "--status completed"); then
  gh run download "$id" -R "$REPO" -n zap-report -D external/zap && add_source zap "$url"
fi

if read -r id url conclusion < <(latest_run codeql.yml "--status completed"); then
  open_alerts=$(gh api "repos/$REPO/code-scanning/alerts?state=open&tool_name=CodeQL&per_page=100" \
    --jq '[.[] | select(.rule.security_severity_level == "high" or .rule.security_severity_level == "critical")] | length' 2>/dev/null)
  if [ -n "$open_alerts" ]; then
    if [ "$conclusion" = "success" ] && [ "$open_alerts" = "0" ]; then echo pass > external/codeql.txt; else echo fail > external/codeql.txt; fi
    add_source codeql "$url"
  fi
fi

echo "$sources" > external/sources.json
find external -type f | sort
