import test from 'node:test';
import assert from 'node:assert/strict';
import {crossGains,tempoRate} from './web/studio.mjs';
test('crossfader equal-power endpoints and center',()=>{assert.deepEqual(crossGains(0),[1,0]);const end=crossGains(1);assert.ok(end[0]<1e-10);assert.equal(end[1],1);for(const x of [0,.1,.5,.75,1]){const [a,b]=crossGains(x);assert.ok(Math.abs(a*a+b*b-1)<1e-10);}});
test('tempo matching stays inside supported playback rates',()=>{assert.equal(tempoRate(120,128),128/120);assert.equal(tempoRate(40,240),2);assert.equal(tempoRate(240,40),.5);});
