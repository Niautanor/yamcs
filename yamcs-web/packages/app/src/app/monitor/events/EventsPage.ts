import { Component, ChangeDetectionStrategy } from '@angular/core';

import { YamcsService } from '../../core/services/YamcsService';
import { EventsDataSource } from './EventsDataSource';
import { Title } from '@angular/platform-browser';
import { GetEventsOptions, DownloadEventsOptions } from '@yamcs/client';
import { FormGroup, FormControl, Validators } from '@angular/forms';
import { BehaviorSubject } from 'rxjs/BehaviorSubject';

const defaultInterval = 'PT1H';

@Component({
  templateUrl: './EventsPage.html',
  styleUrls: ['./EventsPage.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventsPage {

  validStart: Date | null;
  validStop: Date | null;

  // Same as filter.interval but only updates after 'apply' in case of custom dates
  // This allows showing visual indicators for the visible data set before a custom
  // range is actually applied.
  appliedInterval: string;

  filter = new FormGroup({
    interval: new FormControl(defaultInterval),
    customStart: new FormControl(null, [
      Validators.pattern(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/),
    ]),
    customStop: new FormControl(null, [
      Validators.pattern(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/),
    ]),
  });

  dataSource: EventsDataSource;
  displayedColumns = ['severity', 'gentime', 'message', 'type', 'source', 'rectime'];

  downloadURL$ = new BehaviorSubject<string | null>(null);

  constructor(private yamcs: YamcsService, title: Title) {
    title.setTitle('Events - Yamcs');
    this.dataSource = new EventsDataSource(yamcs);

    this.validStop = new Date(); // TODO use mission time
    this.validStart = this.subtractDuration(this.validStop, defaultInterval);
    this.appliedInterval = defaultInterval;
    this.loadData();

    this.filter.get('interval')!.valueChanges.forEach(nextInterval => {
      if (nextInterval === 'CUSTOM') {
        const customStart = this.validStart || new Date();
        const customStop = this.validStop || new Date();
        this.filter.get('customStart')!.setValue(customStart.toISOString());
        this.filter.get('customStop')!.setValue(customStop.toISOString());
      } else if (nextInterval === 'NO_LIMIT') {
        this.validStart = null;
        this.validStop = null;
        this.appliedInterval = nextInterval;
        this.loadData();
      } else {
        this.validStop = new Date(); // TODO use mission time
        this.validStart = this.subtractDuration(this.validStop, nextInterval);
        this.appliedInterval = nextInterval;
        this.loadData();
      }
    });
  }

  jumpToNow() {
    const interval = this.filter.value['interval'];
    if (interval === 'NO_LIMIT') {
      // NO_LIMIT may include future data under erratic conditions. Reverting
      // to the default interval is more in line with the wording 'jump to now'.
      this.filter.get('interval')!.setValue(defaultInterval);
    } else {
      this.validStop = new Date(); // TODO use mission time
      this.validStart = this.subtractDuration(this.validStop, interval);
      this.loadData();
    }
  }

  applyCustomDates() {
    this.validStart = new Date(this.filter.value['customStart']);
    this.validStop = new Date(this.filter.value['customStop']);
    this.appliedInterval = 'CUSTOM';
    this.loadData();
  }

  /**
   * Loads the first page of data within validStart and validStop
   */
  loadData() {
    const options: GetEventsOptions = {};
    if (this.validStart) {
      options.start = this.validStart.toISOString();
    }
    if (this.validStop) {
      options.stop = this.validStop.toISOString();
    }

    const dlOptions: DownloadEventsOptions = {
      format: 'csv',
    };
    if (this.validStart) {
      dlOptions.start = this.validStart.toISOString();
    }
    if (this.validStop) {
      dlOptions.stop = this.validStop.toISOString();
    }

    const instanceClient = this.yamcs.getSelectedInstance();
    this.dataSource.loadEvents(options).then(events => {
      const downloadURL = instanceClient.getEventsDownloadURL(dlOptions);
      this.downloadURL$.next(downloadURL);
    });
  }

  loadMoreData() {
    const options: GetEventsOptions = {};
    if (this.validStart) {
      options.start = this.validStart.toISOString();
    }

    this.dataSource.loadMoreData(options);
  }

  private subtractDuration(date: Date, duration: string) {
    let result;
    switch (duration) {
      case 'PT1H':
        result = new Date();
        result.setUTCHours(date.getUTCHours() - 1);
        return result;
      case 'PT6H':
        result = new Date();
        result.setUTCHours(date.getUTCHours() - 6);
        return result;
      case 'P1D':
        result = new Date();
        result.setUTCHours(date.getUTCHours() - 24);
        return result;
      default:
        console.error('Unexpected duration ', duration);
        return date;
    }
  }
}

