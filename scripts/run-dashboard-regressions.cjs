// Original regressions plus the daily check-in center, with isolated browser profiles.
const fs=require('fs'),path=require('path'),cp=require('child_process');
const root=path.resolve(__dirname,'..');
const scripts=[
  "tests/dashboard-startup-theme.cjs",
  "tests/dashboard-cache-usage.cjs",
  "tests/dashboard-usage-hours.cjs",
  "tests/dashboard-usage-range.cjs",
  "tests/dashboard-dock-lens.cjs",
  "tests/dashboard-glass-switch.cjs",
  "tests/dashboard-card-morph.cjs",
  "tests/dashboard-provider-route-motion.cjs",
  "tests/dashboard-interaction-motion.cjs",
  "tests/dashboard-glass-menu.cjs",
  "tests/dashboard-disclosure-motion.cjs",
  "tests/dashboard-gateway-motion.cjs",
  "tests/dashboard-account-routing.cjs",
  "tests/dashboard-audit59.cjs",
  "tests/dashboard-account-management.cjs",
  "tests/dashboard-prefix-ids.cjs",
  "tests/dashboard-model-selection.cjs",
  "tests/dashboard-model-policy-races.cjs",
  "tests/dashboard-model-selection-browser.cjs",
  "tests/dashboard-app-updates.cjs",
  "tests/dashboard-stream-lifecycle.cjs",
  "tests/dashboard-provider-badges.cjs",
  "tests/dashboard-account-json-files.cjs",
  "tests/dashboard-stream-observation.cjs",
  "tests/dashboard-catpaw-auth-feedback.cjs",
  "tests/provider-upstream-defaults.cjs",
  "tests/dashboard-checkin-center.cjs",
  "tests/dashboard-home-quota.cjs",
  "tests/dashboard-setup-key.cjs",
  "tests/dashboard-setup-motion.cjs",
  "tests/dashboard-back-navigation.cjs",
  "tests/dashboard-endpoint-probe.cjs",
  "tests/dashboard-model-diagnostics.cjs",
  "tests/dashboard-gift-safety.cjs",
  "tests/dashboard-model-names.cjs",
  "tests/dashboard-model-billing.cjs",
  "tests/dashboard-routing-audit.cjs",
  "tests/dashboard-exposed.cjs",
  "tests/dashboard-background.cjs",
  "tests/dashboard-background-experiment.cjs",
  "tests/dashboard-heatmap.cjs",
  "tests/dashboard-live-status.cjs",
  "tests/dashboard-provider-management.cjs",
  "tests/dashboard-network.cjs",
  "tests/dashboard-usage-routing.cjs",
  "tests/dashboard-outbound.cjs",
  "tests/dashboard-resilience.cjs",
  "tests/dashboard-diagnostics.cjs",
  "tests/dashboard-combined.cjs",
  "tests/dashboard-q2.cjs",
  "tests/dashboard-image-body.cjs",
  "tests/dashboard-trae.cjs",
  "tests/dashboard-account-ops.cjs",
  "tests/dashboard-loomy-sync.cjs",
  "tests/dashboard-failover-save.cjs",
  "tests/dashboard-gateway-access.cjs",
  "tests/dashboard-listen-plan.cjs",
  "tests/dashboard-management-boundary.cjs",
  "tests/dashboard-minimax-pools.cjs",
  "tests/dashboard-workbuddy-harness.cjs",
  "tests/trae-login-recovery.cjs",
  "scripts/beta-dashboard-xss.cjs",
  "tests/dashboard-legacy-key-migration.cjs"
];
const build=path.join(root,'build');fs.mkdirSync(build,{recursive:true});
const temporary=fs.mkdtempSync(path.join(build,'dashboard-regression-'));
let passed=0,failures=[];
try {
  for(const script of scripts){
    const result=cp.spawnSync(process.execPath,[script],{cwd:root,encoding:'utf8',timeout:90000,maxBuffer:16*1024*1024,
      env:{...process.env,ALLIN_TEST_TMP:temporary}});
    if(!result.error && result.status===0){passed++;console.log('PASS '+script);}
    else {failures.push(script);console.error('FAIL '+script,String(result.error||result.stderr||result.stdout).slice(-4000));}
  }
} finally {fs.rmSync(temporary,{recursive:true,force:true,maxRetries:20,retryDelay:100});}
console.log(JSON.stringify({total:scripts.length,passed,failed:failures.length,failures}));
process.exitCode=failures.length?1:0;
