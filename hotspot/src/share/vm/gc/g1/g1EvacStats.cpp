/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "memory/allocation.inline.hpp"
#include "gc/g1/g1_globals.hpp"
#include "gc/g1/g1EvacStats.hpp"
#include "gc/shared/gcId.hpp"
#include "logging/log.hpp"
#include "trace/tracing.hpp"

void G1EvacStats::log_plab_allocation() {
  PLABStats::log_plab_allocation();
  log_debug(gc, plab)("%s other allocation: "
                      "region end waste: " SIZE_FORMAT "B, "
                      "regions filled: %u, "
                      "direct allocated: " SIZE_FORMAT "B, "
                      "failure used: " SIZE_FORMAT "B, "
                      "failure wasted: " SIZE_FORMAT "B",
                      _description,
                      _region_end_waste * HeapWordSize,
                      _regions_filled,
                      _direct_allocated * HeapWordSize,
                      _failure_used * HeapWordSize,
                      _failure_waste * HeapWordSize);
}

void G1EvacStats::adjust_desired_plab_sz() {
  log_plab_allocation();

  if (!ResizePLAB) {
    // Clear accumulators for next round.
    reset();
    return;
  }

  assert(is_object_aligned(max_size()) && min_size() <= max_size(),
         "PLAB clipping computation may be incorrect");

  if (_allocated == 0) {
    assert((_unused == 0),
           "Inconsistency in PLAB stats: "
           "_allocated: " SIZE_FORMAT ", "
           "_wasted: " SIZE_FORMAT ", "
           "_region_end_waste: " SIZE_FORMAT ", "
           "_unused: " SIZE_FORMAT ", "
           "_used  : " SIZE_FORMAT,
           _allocated, _wasted, _region_end_waste, _unused, used());
    _allocated = 1;
  }
  // The size of the PLAB caps the amount of space that can be wasted at the
  // end of the collection. In the worst case the last PLAB could be completely
  // empty.
  // This allows us to calculate the new PLAB size to achieve the
  // TargetPLABWastePct given the latest memory usage and that the last buffer
  // will be G1LastPLABAverageOccupancy full.
  //
  // E.g. assume that if in the current GC 100 words were allocated and a
  // TargetPLABWastePct of 10 had been set.
  //
  // So we could waste up to 10 words to meet that percentage. Given that we
  // also assume that that buffer is typically half-full, the new desired PLAB
  // size is set to 20 words.
  //
  // The amount of allocation performed should be independent of the number of
  // threads, so should the maximum waste we can spend in total. So if
  // we used n threads to allocate, each of them can spend maximum waste/n words in
  // a first rough approximation. The number of threads only comes into play later
  // when actually retrieving the actual desired PLAB size.
  //
  // After calculating this optimal PLAB size the algorithm applies the usual
  // exponential decaying average over this value to guess the next PLAB size.
  //
  // We account region end waste fully to PLAB allocation (in the calculation of
  // what we consider as "used_for_waste_calculation" below). This is not
  // completely fair, but is a conservative assumption because PLABs may be sized
  // flexibly while we cannot adjust inline allocations.
  // Allocation during GC will try to minimize region end waste so this impact
  // should be minimal.
  //
  // We need to cover overflow when calculating the amount of space actually used
  // by objects in PLABs when subtracting the region end waste.
  // Region end waste may be higher than actual allocation. This may occur if many
  // threads do not allocate anything but a few rather large objects. In this
  // degenerate case the PLAB size would simply quickly tend to minimum PLAB size,
  // which is an okay reaction.
  size_t const used_for_waste_calculation = used() > _region_end_waste ? used() - _region_end_waste : 0;

  size_t const total_waste_allowed = used_for_waste_calculation * TargetPLABWastePct;
  size_t const cur_plab_sz = (size_t)((double)total_waste_allowed / G1LastPLABAverageOccupancy);
  // Take historical weighted average
  _filter.sample(cur_plab_sz);
  _desired_net_plab_sz = MAX2(min_size(), (size_t)_filter.average());

  log_sizing(cur_plab_sz, _desired_net_plab_sz);
  // Clear accumulators for next round.
  reset();
}

G1EvacStats::G1EvacStats(const char* description, size_t desired_plab_sz_, unsigned wt) :
  PLABStats(description, desired_plab_sz_, wt),
  _region_end_waste(0),
  _regions_filled(0),
  _direct_allocated(0),
  _failure_used(0),
  _failure_waste(0) {
}


G1EvacStats::~G1EvacStats() { }
