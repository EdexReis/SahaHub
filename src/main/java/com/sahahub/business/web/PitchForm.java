package com.sahahub.business.web;

import java.math.BigDecimal;

import com.sahahub.business.app.BranchAdminService;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.Surface;

/** Saha ekleme/düzenleme formu. Kurallar BranchAdminService'te doğrulanır. */
public class PitchForm {

	private String name;
	private String description;
	private int capacityPlayers = 14;
	private Integer lengthM;
	private Integer widthM;
	private boolean indoor;
	private Surface surface = Surface.ARTIFICIAL_TURF;
	private boolean lighting = true;
	private boolean parking;
	private boolean shower;
	private boolean lockerRoom;
	private int slotMinutes = 60;
	private int slotStepMinutes = 60;
	private int bufferMinutes;
	/** Yalnızca yeni sahada; sonra Fiyatlandırma ekranından değişir. */
	private BigDecimal basePrice;

	public static PitchForm from(Pitch p) {
		PitchForm f = new PitchForm();
		f.name = p.getName();
		f.description = p.getDescription();
		f.capacityPlayers = p.getCapacityPlayers();
		f.lengthM = p.getLengthM();
		f.widthM = p.getWidthM();
		f.indoor = p.isIndoor();
		f.surface = p.getSurface();
		f.lighting = p.isHasLighting();
		f.parking = p.isHasParking();
		f.shower = p.isHasShower();
		f.lockerRoom = p.isHasLockerRoom();
		f.slotMinutes = p.getSlotMinutes();
		f.slotStepMinutes = p.getSlotStepMinutes();
		f.bufferMinutes = p.getBufferMinutes();
		f.basePrice = p.getBaseHourlyPrice();
		return f;
	}

	public BranchAdminService.PitchCommand toCommand() {
		return new BranchAdminService.PitchCommand(name, description, capacityPlayers, lengthM, widthM, indoor, surface,
				lighting, parking, shower, lockerRoom, slotMinutes, slotStepMinutes, bufferMinutes);
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public int getCapacityPlayers() {
		return capacityPlayers;
	}

	public void setCapacityPlayers(int capacityPlayers) {
		this.capacityPlayers = capacityPlayers;
	}

	public Integer getLengthM() {
		return lengthM;
	}

	public void setLengthM(Integer lengthM) {
		this.lengthM = lengthM;
	}

	public Integer getWidthM() {
		return widthM;
	}

	public void setWidthM(Integer widthM) {
		this.widthM = widthM;
	}

	public boolean isIndoor() {
		return indoor;
	}

	public void setIndoor(boolean indoor) {
		this.indoor = indoor;
	}

	public Surface getSurface() {
		return surface;
	}

	public void setSurface(Surface surface) {
		this.surface = surface;
	}

	public boolean isLighting() {
		return lighting;
	}

	public void setLighting(boolean lighting) {
		this.lighting = lighting;
	}

	public boolean isParking() {
		return parking;
	}

	public void setParking(boolean parking) {
		this.parking = parking;
	}

	public boolean isShower() {
		return shower;
	}

	public void setShower(boolean shower) {
		this.shower = shower;
	}

	public boolean isLockerRoom() {
		return lockerRoom;
	}

	public void setLockerRoom(boolean lockerRoom) {
		this.lockerRoom = lockerRoom;
	}

	public int getSlotMinutes() {
		return slotMinutes;
	}

	public void setSlotMinutes(int slotMinutes) {
		this.slotMinutes = slotMinutes;
	}

	public int getSlotStepMinutes() {
		return slotStepMinutes;
	}

	public void setSlotStepMinutes(int slotStepMinutes) {
		this.slotStepMinutes = slotStepMinutes;
	}

	public int getBufferMinutes() {
		return bufferMinutes;
	}

	public void setBufferMinutes(int bufferMinutes) {
		this.bufferMinutes = bufferMinutes;
	}

	public BigDecimal getBasePrice() {
		return basePrice;
	}

	public void setBasePrice(BigDecimal basePrice) {
		this.basePrice = basePrice;
	}

}
